package games.beiming.drive.fs;

import games.beiming.drive.storage.BlobGcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回收站到期清理 + 孤儿 blob 兜底清扫。
 *
 * <p>策略：移入回收站的条目保留 {@code drive.trash.retention-days} 天（默认 30），
 * 到期后自动彻底删除并释放磁盘。计时基准是 {@code trashed_at}。
 *
 * <p><b>为什么按「顶层条目」而不是按节点逐个数天数</b>：
 * 删文件夹时整棵子树的 trashed_at 是同一时刻，父子一起到期，数顶层等价、还少一半工作量；
 * 更要紧的是，只按顶层删能保证子树整棵一起走，不会出现「文件夹还在、里面文件被清了」的半截状态。
 *
 * <p><b>时间基准放数据库</b>（{@code now()}）而不是 JVM 时钟：
 * 容器 TZ 配错或宿主机时区漂移时，两边算出来的「30 天前」会不一样，
 * 一边是 UTC 一边是 +08:00 就差出 8 小时，可能把还没到期的提前清了。
 *
 * <p>清理完再跑一次全盘对账，收掉没有单一删除入口的孤儿（换头像留下的旧图等）。
 * 两者都失败也不互相影响。
 */
@Component
public class TrashCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TrashCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final FsPurgeService purge;
    private final BlobGcService gc;
    private final boolean enabled;
    private final int retentionDays;

    public TrashCleanupJob(JdbcTemplate jdbc, FsPurgeService purge, BlobGcService gc,
                           @Value("${drive.trash.enabled:true}") boolean enabled,
                           @Value("${drive.trash.retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.purge = purge;
        this.gc = gc;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** 每天凌晨 3 点。放在低峰，日志也好认。 */
    @Scheduled(cron = "${drive.trash.cron:0 0 3 * * *}")
    public void run() {
        if (!enabled) return;
        expireTrash();
        collectOrphans();
    }

    /**
     * 清掉过期的顶层条目。返回清掉的条数，方便测试直接断言。
     *
     * <p>查出 id 的快照再逐条处理：整批一条 SQL 删掉的话，
     * 某一条因为并发（用户正好在同一个文件上点「彻底删除」）失败会连带整批回滚。
     */
    public int expireTrash() {
        List<Long> expired = jdbc.queryForList(
                "select n.id from drv_node n where n.status='TRASHED' " +
                        "and n.parent_id is not null " +
                        "and n.trashed_at is not null " +
                        "and n.trashed_at < now() - make_interval(days => ?) " +
                        "and not exists (select 1 from drv_node p " +
                        "                where p.id=n.parent_id and p.status='TRASHED')",
                Long.class, retentionDays);
        if (expired.isEmpty()) {
            log.debug("回收站清理：没有到期条目（保留 {} 天）", retentionDays);
            return 0;
        }

        int purged = 0;
        long freed = 0;
        for (Long id : expired) {
            try {
                // 逐条看 owner：清理任务没有「当前登录用户」，得自己把 owner 带出来
                List<Long> owners = jdbc.queryForList(
                        "select owner_id from drv_node where id=?", Long.class, id);
                if (owners.isEmpty()) continue;   // 这轮之前已经被删了
                FsPurgeService.Result r = purge.purgeOne(owners.get(0), id);
                purged += r.purgedEntries();
                freed += r.freedBytes();
            } catch (RuntimeException e) {
                log.warn("回收站清理：节点 {} 删除失败（下轮再试）: {}", id, e.toString());
            }
        }
        log.info("回收站清理完成：保留 {} 天，清掉 {} 条顶层条目，释放 {}B",
                retentionDays, purged, freed);
        return purged;
    }

    /** 全盘 blob 对账。孤儿回收失败不该让整个任务变红。 */
    public void collectOrphans() {
        try {
            BlobGcService.GcReport r = gc.collect(false);
            if (r.deleted() > 0 || r.skippedRecent() > 0) {
                log.info("blob 对账：扫描 {}，有人引用 {}，太新跳过 {}，清掉 {}，释放 {}B",
                        r.scanned(), r.referenced(), r.skippedRecent(),
                        r.deleted(), r.reclaimedBytes());
            }
        } catch (RuntimeException e) {
            log.warn("blob 对账失败: {}", e.toString());
        }
    }
}