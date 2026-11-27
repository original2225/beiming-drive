package games.beiming.drive.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 孤儿 blob 清扫。
 *
 * <p>为什么需要它：blob 按 sha256 天然去重，代价是「删一条节点记录」和「删一份文件实体」
 * 不再是一回事 —— 同一个 sha 可能被别的节点、或者某个用户的头像引用着。
 * 反过来，节点删光之后实体也没人管了。没有这层回收，用户「彻底删除」后磁盘一点不变，
 * 配额却已经减了 —— 账和盘对不上。
 *
 * <p>两种用法：
 * <ul>
 *   <li>{@link #releaseIfOrphan} —— 精确释放。purge 之后立刻调，用户当场就能看到空间回来。
 *       只动传进来的那几个 sha，不全盘扫。</li>
 *   <li>{@link #collect} —— 全盘对账。定时任务跑的兜底，收掉头像换图、历史遗留等
 *       没有单一删除入口的孤儿。</li>
 * </ul>
 *
 * <p><b>安全边界</b>：只删「长得像 blob」的文件（{@code root/ab/cd/<64位hex>}）。
 * 根目录下的 {@code up-*.part} 上传临时文件、以及任何别的形状一律不碰 ——
 * 清扫任务不该有本事删掉它认不出的东西。另外有 {@code gc-grace-minutes} 冷静期：
 * 刚写进磁盘、节点还没入库的 blob 正在这个窗口里，不能当成孤儿。
 */
@Service
public class BlobGcService {

    private static final Logger log = LoggerFactory.getLogger(BlobGcService.class);

    /** blob 文件名就是 sha256 的小写十六进制。形状不对的一律不认。 */
    private static final Pattern SHA_NAME = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;
    private final Duration grace;

    public BlobGcService(JdbcTemplate jdbc, BlobStore blobs,
                         @Value("${drive.storage.gc-grace-minutes:30}") long graceMinutes) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.grace = Duration.ofMinutes(Math.max(0, graceMinutes));
    }

    /** 一次清扫的结果。字段都可序列化，直接给管理接口当响应。 */
    public record GcReport(boolean dryRun, int scanned, int referenced, int skippedRecent,
                           long deleted, long reclaimedBytes, List<String> samples) {
    }

    /**
     * 引用集：**所有**文件节点（含回收站里的）+ 当前头像。
     *
     * <p>回收站里的节点必须算引用 —— 它还没被彻底删除，用户随时可以恢复，
     * 这时候把实体删了，恢复出来就是个空壳。
     */
    private Set<String> referencedShas() {
        Set<String> set = new HashSet<>();
        for (String s : jdbc.queryForList(
                "select distinct sha256 from drv_node where sha256 is not null", String.class)) {
            set.add(s);
        }
        for (String s : jdbc.queryForList(
                "select avatar_sha from drv_user where avatar_sha is not null", String.class)) {
            set.add(s);
        }
        return set;
    }

    /**
     * 全盘对账：扫出所有 blob，删掉没人引用的。
     *
     * @param dryRun true 时只统计不动手，用来先看会删什么
     */
    public GcReport collect(boolean dryRun) {
        Path root = blobs.root();
        if (!Files.isDirectory(root)) {
            return new GcReport(dryRun, 0, 0, 0, 0L, 0L, List.of());
        }
        Set<String> referenced = referencedShas();
        Instant threshold = Instant.now().minus(grace);

        int scanned = 0;
        int hitReferenced = 0;
        int skippedRecent = 0;
        long deleted = 0;
        long reclaimed = 0;
        List<String> samples = new ArrayList<>();

        // 只往下走三层：root/ab/cd/sha256。再深就不认了。
        try (Stream<Path> walk = Files.walk(root, 3)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (!SHA_NAME.matcher(name).matches()) continue;   // 临时文件、杂项，不碰
                if (p.getNameCount() - root.getNameCount() != 3) continue;
                scanned++;

                if (referenced.contains(name)) {
                    hitReferenced++;
                    continue;
                }
                if (isTooFresh(p, threshold)) {
                    skippedRecent++;
                    continue;
                }

                long size = sizeOf(p);
                if (samples.size() < 20) samples.add(name);
                if (!dryRun) {
                    try {
                        Files.deleteIfExists(p);
                        deleted++;
                        reclaimed += size;
                    } catch (IOException e) {
                        log.warn("删除孤儿 blob 失败 {}: {}", name, e.toString());
                    }
                } else {
                    deleted++;
                    reclaimed += size;
                }
            }
        } catch (IOException e) {
            log.warn("遍历 blob 目录失败: {}", e.toString());
        }

        log.info("blob 清扫{}完成：扫描={} 有人用={} 太新跳过={} 删除={} 释放={}B",
                dryRun ? "（预演）" : "", scanned, hitReferenced, skippedRecent, deleted, reclaimed);
        return new GcReport(dryRun, scanned, hitReferenced, skippedRecent, deleted, reclaimed, samples);
    }

    /**
     * 精确释放：给定一批 sha，逐个确认「库和头像都不再引用」后删实体。
     *
     * <p>purge 之后调用。只处理传进来的，不扫盘 —— 一次彻底删除只涉及几个文件，
     * 没必要为了释放它们去遍历整个存储目录。
     *
     * <p><b>这里不设冷静期</b>，和 {@link #collect} 不一样。区别在数据来源：
     * 传进来的 sha 是从库里查出来的（刚被删掉的节点），说明它们早就入库了，
     * 不存在「正在上传、节点还没写」的中间态。用户点了彻底删除就该当场看到空间回来，
     * 让他等 30 分钟纯属把内部保护机制漏到交互上。
     */
    public record Release(List<String> removed, long bytes) {
        public int count() {
            return removed.size();
        }
    }

    public Release releaseIfOrphan(Collection<String> shas) {
        if (shas == null || shas.isEmpty()) return new Release(List.of(), 0L);
        List<String> removed = new ArrayList<>();
        long bytes = 0;
        for (String sha : new LinkedHashSet<>(shas)) {
            if (sha == null || !SHA_NAME.matcher(sha).matches()) continue;
            Integer still = jdbc.queryForObject(
                    "select (select count(*) from drv_node where sha256=?)" +
                            " + (select count(*) from drv_user where avatar_sha=?)",
                    Integer.class, sha, sha);
            if (still != null && still > 0) continue;      // 还有人在用，留着

            Path p = blobs.pathOf(sha);
            if (!Files.isRegularFile(p)) continue;
            long size = sizeOf(p);
            try {
                Files.deleteIfExists(p);
                removed.add(sha);
                bytes += size;
            } catch (IOException e) {
                log.warn("释放 blob 失败 {}: {}", sha, e.toString());
            }
        }
        if (!removed.isEmpty()) {
            log.info("释放孤儿 blob {} 个，回收 {}B", removed.size(), bytes);
        }
        return new Release(removed, bytes);
    }

    private static boolean isTooFresh(Path p, Instant threshold) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isAfter(threshold);
        } catch (IOException e) {
            return true;    // 拿不到时间就保守当成新的，不删
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }
}