package games.beiming.drive.storage;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * 本地对象存储：blob 按 sha256 分层存放（前 2/2 位做目录），天然去重。
 * 将来换 S3/MinIO 只要替换这个类。
 */
@Service
public class BlobStore {

    private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

    private final Path root;

    public BlobStore(@Value("${drive.storage.root}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(root);
        log.info("blob store root = {}", root);
    }

    /** 存储根目录。给孤儿 blob 清扫（{@link BlobGcService}）遍历用。 */
    public Path root() {
        return root;
    }

    public Path pathOf(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, "非法 blob key");
        }
        return root.resolve(sha256.substring(0, 2))
                .resolve(sha256.substring(2, 4))
                .resolve(sha256);
    }

    public boolean exists(String sha256) {
        return sha256 != null && Files.exists(pathOf(sha256));
    }

    /**
     * 把输入流写入 blob，返回 sha256 与小节。
     * 先写临时文件再原子移动，避免半截文件被当成有效 blob。
     */
    public record Stored(String sha256, long size) {
    }

    public Stored put(InputStream in) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(root, "up-", ".part");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long size;
            try (OutputStream os = new DigestOutputStream(
                    Files.newOutputStream(tmp, StandardOpenOption.WRITE), md)) {
                size = in.transferTo(os);
            }
            String sha = HexFormat.of().formatHex(md.digest());
            Path target = pathOf(sha);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                // 已存在同内容，直接复用（去重）
                Files.deleteIfExists(tmp);
            } else {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            return new Stored(sha, size);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, "写入存储失败: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public InputStream open(String sha256) {
        try {
            return Files.newInputStream(pathOf(sha256));
        } catch (IOException e) {
            throw new DriveException(ErrorCode.NOT_FOUND, "文件内容不存在");
        }
    }

    public FileChannel openChannel(String sha256) {
        try {
            return FileChannel.open(pathOf(sha256), StandardOpenOption.READ);
        } catch (IOException e) {
            throw new DriveException(ErrorCode.NOT_FOUND, "文件内容不存在");
        }
    }

    public long sizeOf(String sha256) {
        try {
            return Files.size(pathOf(sha256));
        } catch (IOException e) {
            return 0L;
        }
    }

    public long totalBytes() {
        if (!Files.exists(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}