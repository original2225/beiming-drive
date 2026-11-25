package games.beiming.drive.remote;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.fs.FileService;
import games.beiming.drive.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 专属多线程分段下载器内核。
 *
 * 设计要点：
 * 1. 先 HEAD/GET 探 Range 支持，拿 Content-Length。
 * 2. 支持 Range -> 切片并发；不支持 -> 退化成单线程流式下载（仍可用）。
 * 3. 每片写独立的 .part 文件，中断后已完成片保留，支持续传。
 * 4. chunks_total / chunks_done 是真实调度计数，不靠 sha256 判断是否并发。
 */
@Service
public class RemoteDownloadService {

    private static final Logger log = LoggerFactory.getLogger(RemoteDownloadService.class);

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;
    private final FileService files;

    private final Path workDir;
    private final int chunkSize;
    private final int workersMax;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "remote-dl");
        t.setDaemon(true);
        return t;
    });

    /** 正在跑的任务，用于取消。 */
    private final Map<String, JobHandle> running = new ConcurrentHashMap<>();

    public RemoteDownloadService(JdbcTemplate jdbc, BlobStore blobs, FileService files,
                                 @Value("${drive.remote.work-dir}") String workDir,
                                 @Value("${drive.remote.chunk-size:8388608}") int chunkSize,
                                 @Value("${drive.remote.workers-max:32}") int workersMax) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.files = files;
        this.workDir = Paths.get(workDir).toAbsolutePath();
        this.chunkSize = chunkSize;
        this.workersMax = workersMax;
        try {
            Files.createDirectories(this.workDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建下载工作目录", e);
        }
    }

    public record Job(String id, String url, String filename, Long targetParent,
                      int workers, String status, Long totalSize, long doneBytes,
                      Integer chunkSize, int chunksTotal, int chunksDone,
                      Long fileNodeId, String error) {
    }

    private static final RowMapper<Job> MAPPER = (rs, i) -> new Job(
            rs.getString("id"), rs.getString("url"), rs.getString("filename"),
            (Long) rs.getObject("target_parent"), rs.getInt("workers"),
            rs.getString("status"),
            (Long) rs.getObject("total_size"), rs.getLong("done_bytes"),
            (Integer) rs.getObject("chunk_size"),
            rs.getInt("chunks_total"), rs.getInt("chunks_done"),
            (Long) rs.getObject("file_node_id"), rs.getString("error"));

    private static class JobHandle {
        volatile boolean canceled = false;
        final AtomicLong doneBytes = new AtomicLong();
    }

    public Job byId(long ownerId, String id) {
        List<Job> rows = jdbc.query(
                "select * from drv_remote_job where id=? and owner_id=?", MAPPER, id, ownerId);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    public List<Job> list(long ownerId) {
        return jdbc.query("select * from drv_remote_job where owner_id=? " +
                "order by created_at desc limit 100", MAPPER, ownerId);
    }

    @Transactional
    public Job create(long ownerId, String url, String filename, Long targetParent, Integer workers) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "只支持 http/https 直链");
        }
        int w = workers == null || workers <= 0 ? 8 : Math.min(workers, workersMax);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String name = (filename == null || filename.isBlank())
                ? guessName(url) : filename;
        jdbc.update("insert into drv_remote_job(id, owner_id, url, filename, target_parent, workers) " +
                        "values (?,?,?,?,?,?)",
                id, ownerId, url, name, targetParent, w);
        return byId(ownerId, id);
    }

    private static String guessName(String url) {
        String p = URI.create(url).getPath();
        if (p == null || p.endsWith("/")) return "download.bin";
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** 异步启动任务。 */
    public void start(long ownerId, String jobId) {
        Job job = byId(ownerId, jobId);
        if (!"PENDING".equals(job.status())) {
            throw new DriveException(ErrorCode.STATE_CONFLICT, "任务不在待启动状态");
        }
        JobHandle h = new JobHandle();
        running.put(jobId, h);
        jdbc.update("update drv_remote_job set status='RUNNING', updated_at=now() where id=?", jobId);
        pool.submit(() -> execute(ownerId, job, h));
    }

    public void cancel(long ownerId, String jobId) {
        Job job = byId(ownerId, jobId);
        JobHandle h = running.get(jobId);
        if (h != null) h.canceled = true;
        jdbc.update("update drv_remote_job set status='CANCELED', updated_at=now() where id=?", jobId);
    }

    /**
     * 删除任务记录。运行中的不给删，必须先 cancel —— 否则后台线程还在写
     * drv_remote_job，行被删掉后会一直报「找不到任务」。
     */
    public void delete(long ownerId, String jobId) {
        Job job = byId(ownerId, jobId);
        String st = job.status();
        if ("PENDING".equals(st) || "RUNNING".equals(st)) {
            throw new DriveException(ErrorCode.CONFLICT, "任务还在进行，先取消再删除");
        }
        running.remove(jobId);
        jdbc.update("delete from drv_remote_job where id=? and owner_id=?", jobId, ownerId);
        // 清掉这个任务的临时分片目录，别把磁盘越堆越满
        try {
            Path dir = workDir.resolve(jobId);
            if (Files.exists(dir)) {
                Files.walk(dir)
                     .sorted(Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {
            // 临时目录清不掉不影响任务记录删除，下次启动也会被清理
        }
    }

    private void execute(long ownerId, Job job, JobHandle h) {
        Path jobDir = workDir.resolve(job.id());
        try {
            Files.createDirectories(jobDir);

            Probe probe = probe(job.url());
            long total = probe.length > 0 ? probe.length : -1;

            if (!probe.rangeSupported || total <= 0) {
                // 退化：单线程，但仍记录 1/1，前端不会误判成"并发成功"
                singleThread(ownerId, job, h, jobDir, probe);
                return;
            }

            int chunks = (int) ((total + chunkSize - 1) / chunkSize);
            jdbc.update("update drv_remote_job set total_size=?, chunk_size=?, chunks_total=?, " +
                    "updated_at=now() where id=?", total, chunkSize, chunks, job.id());

            downloadChunks(job, h, jobDir, total, chunks, probe);
            if (h.canceled) return;

            assembleAndStore(ownerId, job, h, jobDir, total, chunks);

        } catch (DriveException e) {
            fail(job.id(), e.getMessage());
        } catch (Exception e) {
            log.warn("remote job {} failed", job.id(), e);
            fail(job.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running.remove(job.id());
        }
    }

    private record Probe(long length, boolean rangeSupported, String contentType) {
    }

    private Probe probe(String url) throws IOException, InterruptedException {
        HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "BeimingDrive/1.0")
                .build();
        try {
            HttpResponse<Void> r = http.send(head, HttpResponse.BodyHandlers.discarding());
            String ar = r.headers().firstValue("accept-ranges").orElse("");
            long len = r.headers().firstValueAsLong("content-length").orElse(-1L);
            String ct = r.headers().firstValue("content-type").orElse(null);
            if (r.statusCode() / 100 == 2 && len > 0) {
                return new Probe(len, ar.toLowerCase().contains("bytes"), ct);
            }
        } catch (IOException | InterruptedException ignored) {
            // 有的服务器不让 HEAD，下面用 Range: bytes=0-0 再试
        }
        // 用 1 字节 Range 探测
        HttpRequest r0 = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Range", "bytes=0-0")
                .header("User-Agent", "BeimingDrive/1.0")
                .build();
        HttpResponse<Void> r = http.send(r0, HttpResponse.BodyHandlers.discarding());
        String cr = r.headers().firstValue("content-range").orElse("");
        long total = -1;
        if (cr.contains("/")) {
            try {
                total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        boolean rangeOk = r.statusCode() == 206 && total > 0;
        if (!rangeOk) {
            long len = r.headers().firstValueAsLong("content-length").orElse(-1L);
            total = len;
        }
        return new Probe(total, rangeOk, r.headers().firstValue("content-type").orElse(null));
    }

    private void downloadChunks(Job job, JobHandle h, Path jobDir, long total,
                                int chunks, Probe probe) throws InterruptedException {
        long startNs = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        Semaphore limiter = new Semaphore(job.workers());

        for (int i = 0; i < chunks; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                limiter.acquire();
                try {
                    long start = (long) idx * chunkSize;
                    long end = Math.min(start + chunkSize - 1, total - 1);
                    Path part = jobDir.resolve(idx + ".part");
                    if (Files.exists(part) && Files.size(part) == (end - start + 1)) {
                        // 续传：这片已经好了
                        h.doneBytes.addAndGet(end - start + 1);
                        bumpDone(job.id());
                        return null;
                    }
                    fetchChunk(job.url(), start, end, part, h);
                    h.doneBytes.addAndGet(end - start + 1);
                    bumpDone(job.id());
                    updateProgress(job.id(), h.doneBytes.get(), total, startNs);
                } finally {
                    limiter.release();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw new DriveException(ErrorCode.REMOTE_FETCH_FAILED,
                        "分片下载失败: " + e.getCause());
            }
        }
    }

    private void fetchChunk(String url, long start, long end, Path part, JobHandle h)
            throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            if (h.canceled) throw new CancellationException("canceled");
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .header("Range", "bytes=" + start + "-" + end)
                        .header("User-Agent", "BeimingDrive/1.0")
                        .build();
                HttpResponse<Path> res = http.send(req,
                        HttpResponse.BodyHandlers.ofFile(part,
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING));
                if (res.statusCode() != 206 && res.statusCode() != 200) {
                    throw new IOException("HTTP " + res.statusCode());
                }
                return;
            } catch (IOException e) {
                if (++attempt >= 3) throw e;
                Thread.sleep(300L * attempt);
            }
        }
    }

    private void singleThread(long ownerId, Job job, JobHandle h, Path jobDir, Probe probe)
            throws IOException, InterruptedException {
        Path out = jobDir.resolve("single.part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(job.url()))
                .timeout(Duration.ofHours(2))
                .header("User-Agent", "BeimingDrive/1.0")
                .build();
        HttpResponse<Path> res = http.send(req,
                HttpResponse.BodyHandlers.ofFile(out, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode());
        }
        long size = Files.size(out);
        jdbc.update("update drv_remote_job set total_size=?, chunks_total=1, chunks_done=1, " +
                        "done_bytes=?, chunk_size=?, updated_at=now() where id=?",
                size, size, size, job.id());
        store(ownerId, job, out, job.filename(), size);
    }

    private void assembleAndStore(long ownerId, Job job, JobHandle h, Path jobDir,
                                  long total, int chunks) throws IOException {
        Path merged = jobDir.resolve("merged.bin");
        try (OutputStream os = Files.newOutputStream(merged, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < chunks; i++) {
                Path p = jobDir.resolve(i + ".part");
                if (!Files.exists(p)) throw new IOException("缺分片 " + i);
                Files.copy(p, os);
            }
        }
        String expectShape = sha256Of(merged);
        store(ownerId, job, merged, job.filename(), total, expectShape);
        for (int i = 0; i < chunks; i++) {
            Files.deleteIfExists(jobDir.resolve(i + ".part"));
        }
        Files.deleteIfExists(merged);
    }

    private void store(long ownerId, Job job, Path file, String name, long size) {
        store(ownerId, job, file, name, size, null);
    }

    private void store(long ownerId, Job job, Path file, String name, long size, String expect) {
        try (InputStream in = Files.newInputStream(file)) {
            BlobStore.Stored st = blobs.put(in);
            if (expect != null && !expect.equals(st.sha256())) {
                fail(job.id(), "合并后 sha256 校验不一致");
                return;
            }
            FileService.Node node = files.putFile(ownerId, job.targetParent(), name,
                    null, st.sha256(), st.size());
            jdbc.update("update drv_remote_job set status='DONE', file_node_id=?, " +
                            "done_bytes=?, total_size=?, chunks_done=chunks_total, " +
                            "updated_at=now() where id=?",
                    node.id(), st.size(), st.size(), job.id());
        } catch (IOException e) {
            fail(job.id(), e.getMessage());
        }
    }

    private static String sha256Of(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private void bumpDone(String jobId) {
        jdbc.update("update drv_remote_job set chunks_done = chunks_done + 1, updated_at=now() " +
                "where id=?", jobId);
    }

    private void updateProgress(String jobId, long done, long total, long startNs) {
        jdbc.update("update drv_remote_job set done_bytes=?, updated_at=now() where id=?",
                done, jobId);
    }

    private void fail(String jobId, String err) {
        jdbc.update("update drv_remote_job set status='FAILED', error=?, updated_at=now() where id=?",
                err == null ? "unknown" : err.substring(0, Math.min(err.length(), 900)), jobId);
    }
}