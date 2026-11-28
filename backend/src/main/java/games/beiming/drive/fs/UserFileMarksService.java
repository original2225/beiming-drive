package games.beiming.drive.fs;

import games.beiming.drive.auth.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 下载记录 + 收藏。
 *
 * 两件都是「用户和文件的关系」，所以放同一个 service —— 但它们没有别的关联，
 * 别把这里当成一个通用的"关系表管理器"，将来还有别的说法就再拆。
 */
@Service
public class UserFileMarksService {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public UserFileMarksService(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /* ==================== 下载记录 ==================== */

    public record DownloadEntry(long id, Long nodeId, Long parentId, String filename, String path,
                                long size, int times, Instant lastAt) {}

    /**
     * 记一次下载。
     *
     * 同一文件重复下载不刷多行：`on conflict` 把 times 加一、last_at 刷新。
     * 为什么用 upsert 而不是先查再写：并发下两个线程同时下同一个文件，
     * 「先查后写」会两边都查不到然后各插一行，撞唯一索引报 500。
     */
    @Transactional
    public void recordDownload(long userId, Long nodeId, String filename, String path, long size) {
        if (filename == null || filename.isBlank()) return;
        if (nodeId == null) return;   // 没节点就不记，免得堆一堆无主行

        jdbc.update(
                "insert into drv_download_log(user_id, node_id, filename, path, size_bytes) " +
                "values (?,?,?,?,?) " +
                "on conflict (user_id, node_id) where node_id is not null " +
                "do update set times = drv_download_log.times + 1, " +
                "              last_at = now(), " +
                "              filename = excluded.filename, " +
                "              path = excluded.path, " +
                "              size_bytes = excluded.size_bytes",
                userId, nodeId, truncate(filename, 512), truncate(path, 2000), size);
    }

    /**
     * 侧栏「下载记录」。limit 卡在 50，再多侧栏也放不下，
     * 真要全量翻列表去专门页面，不在这个抽屉里做。
     *
     * 带上 parent_id 是为了「点一行就跳到它所在的目录」——
     * 前端不用再从 path 字符串里剥目录名（那个剥法遇到带斜杠的
     * 文件名会剥错，而且 path 格式将来可能变）。
     */
    public List<DownloadEntry> downloadHistory(int limit) {
        long uid = currentUser.require().userId();
        int n = Math.min(Math.max(limit, 1), 50);
        return jdbc.query(
                "select d.id, d.node_id, n.parent_id, d.filename, coalesce(d.path,'') as path, " +
                "       d.size_bytes, d.times, d.last_at " +
                "from drv_download_log d left join drv_node n on n.id = d.node_id " +
                "where d.user_id = ? order by d.last_at desc limit ?",
                (rs, i) -> new DownloadEntry(
                        rs.getLong("id"),
                        rs.getObject("node_id") == null ? null : rs.getLong("node_id"),
                        rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
                        rs.getString("filename"), rs.getString("path"),
                        rs.getLong("size_bytes"), rs.getInt("times"),
                        rs.getTimestamp("last_at").toInstant()),
                uid, n);
    }

    /** 清空自己的下载记录 */
    @Transactional
    public int clearDownloadHistory() {
        return jdbc.update("delete from drv_download_log where user_id = ?",
                currentUser.require().userId());
    }

    /** 删掉单条记录（用户主动移除「不想让它待在列表里」） */
    @Transactional
    public void deleteDownloadEntry(long id) {
        jdbc.update("delete from drv_download_log where id = ? and user_id = ?",
                id, currentUser.require().userId());
    }

    /* ==================== 收藏 ==================== */

    public record StarEntry(long nodeId, String filename, String path, boolean isDir,
                            long size, Instant createdAt) {}

    @Transactional
    public void star(long nodeId) {
        long uid = currentUser.require().userId();
        jdbc.update("insert into drv_star(user_id, node_id) values (?,?) " +
                    "on conflict (user_id, node_id) do nothing", uid, nodeId);
    }

    @Transactional
    public void unstar(long nodeId) {
        jdbc.update("delete from drv_star where user_id = ? and node_id = ?",
                currentUser.require().userId(), nodeId);
    }

    /** 这个文件我收藏了吗 —— 文件列表每行都要问，所以只查一条 */
    public boolean isStarred(long nodeId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from drv_star where user_id = ? and node_id = ?",
                Integer.class, currentUser.require().userId(), nodeId);
        return n != null && n > 0;
    }

    /** 一次性拿到「我收藏了当前目录下哪些文件」，避免每行都发一次请求 */
    public List<Long> starredIn(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return List.of();
        long uid = currentUser.require().userId();
        String in = String.join(",", nodeIds.stream().map(String::valueOf).toList());
        return jdbc.queryForList(
                "select node_id from drv_star where user_id = ? and node_id in (" + in + ")",
                Long.class, uid);
    }

    /**
     * 收藏夹列表。join drv_node 拿当前状态 ——
     * 进回收站（status='TRASHED'）的收藏不显示但也不删，恢复后自动回来。
     *
     * 注意 status 的值是 READY（不是 ACTIVE）。这个库的 node 状态只有
     * READY / TRASHED 两个，写 ACTIVE 会永远查出 0 行 —— 而且不会报错，
     * 只是列表一直空着，很难发现。
     */
    public List<StarEntry> stars(int limit) {
        long uid = currentUser.require().userId();
        int n = Math.min(Math.max(limit, 1), 100);
        return jdbc.query(
                "select s.node_id, n.name, n.path, n.kind, n.size, s.created_at " +
                "from drv_star s join drv_node n on n.id = s.node_id " +
                "where s.user_id = ? and n.status = 'READY' " +
                "order by s.created_at desc limit ?",
                (rs, i) -> new StarEntry(
                        rs.getLong("node_id"), rs.getString("name"), rs.getString("path"),
                        "dir".equals(rs.getString("kind")), rs.getLong("size"),
                        rs.getTimestamp("created_at").toInstant()),
                uid, n);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}