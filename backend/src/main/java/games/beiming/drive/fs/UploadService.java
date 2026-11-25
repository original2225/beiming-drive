package games.beiming.drive.fs;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 分片上传：先建会话，逐片 PUT，最后 complete 合并。
 * 断点续传靠 drv_upload_chunk 记录已收分片，前端问 /status 拿缺失片号。
 */
@Service
public class UploadService {

    private final JdbcTemplate jdbc;
    private final FileService fileService;
    private final Path stagingDir;
    private final int defaultChunkSize;

    public UploadService(JdbcTemplate jdbc, FileService fileService,
                         @Value("${drive.storage.root}") String root,
                         @Value("${drive.upload.default-chunk-size:8388608}") int defaultChunkSize) {
        this.jdbc = jdbc;
        this.fileService = fileService;
        this.stagingDir = Paths.get(root).toAbsolutePath().resolveSibling("staging");
        this.defaultChunkSize = defaultChunkSize;
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建暂存目录: " + stagingDir, e);
        }
    }

    public record Session(String id, int chunkSize, int chunkTotal, Long parentId, String name) {
    }

    public record Status(String id, int chunkTotal, List<Integer> received, List<Integer> missing,
                         boolean complete) {
    }

    private static final RowMapper<Object[]> NONE = (rs, i) -> new Object[0];

    @Transactional
    public Session create(long ownerId, Long parentId, String name, long totalSize, Integer chunkSize) {
        if (totalSize <= 0) throw new DriveException(ErrorCode.BAD_REQUEST, "文件大小必须大于 0");
        int cs = chunkSize == null || chunkSize <= 0 ? defaultChunkSize : chunkSize;
        int total = (int) ((totalSize + cs - 1) / cs);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        jdbc.update("insert into drv_upload(id, owner_id, parent_id, name, total_size, " +
                        "chunk_size, chunk_total) values (?,?,?,?,?,?,?)",
                id, ownerId, parentId, name, totalSize, cs, total);
        return new Session(id, cs, total, parentId, name);
    }

    private void requireOpen(long ownerId, String uploadId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from drv_upload where id=? and owner_id=? and status='OPEN'",
                Integer.class, uploadId, ownerId);
        if (n == null || n == 0) throw new DriveException(ErrorCode.UPLOAD_SESSION_GONE);
    }

    /** 收一片。重复上传同一片视为覆盖（幂等）。 */
    public void putChunk(long ownerId, String uploadId, int idx, InputStream data) {
        requireOpen(ownerId, uploadId);
        Integer total = jdbc.queryForObject(
                "select chunk_total from drv_upload where id=?", Integer.class, uploadId);
        if (total == null || idx < 0 || idx >= total) {
            throw new DriveException(ErrorCode.CHUNK_OUT_OF_RANGE);
        }
        Path part = partPath(uploadId, idx);
        try {
            Files.createDirectories(part.getParent());
            long size = Files.copy(data, part, StandardCopyOption.REPLACE_EXISTING);
            jdbc.update("insert into drv_upload_chunk(upload_id, idx, size) values (?,?,?) " +
                            "on conflict (upload_id, idx) do update set size=excluded.size, " +
                            "received_at=now()",
                    uploadId, idx, (int) size);
            jdbc.update("update drv_upload set received = " +
                    "(select count(*) from drv_upload_chunk where upload_id=?), updated_at=now() " +
                    "where id=?", uploadId, uploadId);
        } catch (IOException e) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, "写入分片失败: " + e.getMessage());
        }
    }

    public Status status(long ownerId, String uploadId) {
        requireOpen(ownerId, uploadId);
        Integer total = jdbc.queryForObject(
                "select chunk_total from drv_upload where id=?", Integer.class, uploadId);
        List<Integer> received = jdbc.queryForList(
                "select idx from drv_upload_chunk where upload_id=? order by idx",
                Integer.class, uploadId);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < (total == null ? 0 : total); i++) {
            if (!received.contains(i)) missing.add(i);
        }
        return new Status(uploadId, total == null ? 0 : total, received, missing,
                missing.isEmpty());
    }

    public record Completed(FileService.Node node, String sha256, long size) {
    }

    /** 合并所有分片 -> blob -> 建文件条目。 */
    @Transactional
    public Completed complete(long ownerId, String uploadId) {
        requireOpen(ownerId, uploadId);
        Object[] row = jdbc.queryForObject(
                "select parent_id, name, total_size, chunk_total from drv_upload where id=?",
                (rs, i) -> new Object[]{rs.getObject("parent_id"), rs.getString("name"),
                        rs.getLong("total_size"), rs.getInt("chunk_total")},
                uploadId);
        Long parentId = row[0] == null ? null : ((Number) row[0]).longValue();
        String name = (String) row[1];
        int total = (Integer) row[3];

        List<Integer> received = jdbc.queryForList(
                "select idx from drv_upload_chunk where upload_id=? order by idx",
                Integer.class, uploadId);
        if (received.size() != total) {
            throw new DriveException(ErrorCode.STATE_CONFLICT,
                    "还有 " + (total - received.size()) + " 个分片未上传");
        }

        Path assembled = stagingDir.resolve(uploadId + ".merged");
        String sha;
        long size;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            size = 0;
            try (var out = Files.newOutputStream(assembled, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int i = 0; i < total; i++) {
                    Path part = partPath(uploadId, i);
                    byte[] buf = Files.readAllBytes(part);
                    md.update(buf);
                    out.write(buf);
                    size += buf.length;
                }
            }
            sha = HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, "合并分片失败: " + e.getMessage());
        }

        // 交给 BlobStore 归档（会再算一次 hash，作为一次自校验）
        games.beiming.drive.storage.BlobStore.Stored stored;
        try (InputStream in = Files.newInputStream(assembled)) {
            stored = fileService.blobStore().put(in);
        } catch (IOException e) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, e.getMessage());
        }
        if (!stored.sha256().equals(sha)) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, "分片合并后校验不一致");
        }

        FileService.Node node = fileService.putFile(ownerId, parentId, name,
                guessMime(name), stored.sha256(), stored.size());

        jdbc.update("update drv_upload set status='DONE', updated_at=now() where id=?", uploadId);
        cleanupParts(uploadId, total);
        try {
            Files.deleteIfExists(assembled);
        } catch (IOException ignored) {
        }
        return new Completed(node, stored.sha256(), stored.size());
    }

    @Transactional
    public void abort(long ownerId, String uploadId) {
        Integer total = jdbc.queryForObject(
                "select chunk_total from drv_upload where id=? and owner_id=?",
                Integer.class, uploadId, ownerId);
        jdbc.update("update drv_upload set status='ABORTED', updated_at=now() where id=?",
                uploadId);
        if (total != null) cleanupParts(uploadId, total);
    }

    private void cleanupParts(String uploadId, int total) {
        for (int i = 0; i < total; i++) {
            try {
                Files.deleteIfExists(partPath(uploadId, i));
            } catch (IOException ignored) {
            }
        }
    }

    private Path partPath(String uploadId, int idx) {
        return stagingDir.resolve(uploadId).resolve(idx + ".part");
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}