package games.beiming.drive.fs;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.storage.BlobStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 文件系统服务：目录树、文件条目、回收站。
 * path 是全路径唯一键；改名/移动要级联更新子树的 path 前缀。
 */
@Service
public class FileService {

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;
    /** 搜索索引同步。用 setter 注入，因为 FsSearchSync 依赖 FileService.Node 的构造，直接构造注入会成环。 */
    private FsSearchSync searchSync;

    public FileService(JdbcTemplate jdbc, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSearchSync(FsSearchSync searchSync) {
        this.searchSync = searchSync;
    }

    /** 索引同步是可选的：没装配就跳过（比如单元测试里） */
    private void indexPut(Node n) {
        if (searchSync != null && n != null && !"TRASHED".equals(n.status())) searchSync.put(n);
    }

    private void indexRemove(long id) {
        if (searchSync != null) searchSync.remove(id);
    }

    /** 供分片上传在合并后写入 blob 使用，避免再注入一次。 */
    public BlobStore blobStore() {
        return blobs;
    }

    public record Node(long id, long ownerId, Long parentId, String name, String path,
                       String kind, long size, String mime, String sha256,
                       String status, Instant createdAt, Instant updatedAt) {

        public boolean isDir() {
            return "dir".equals(kind);
        }
    }

    private static final RowMapper<Node> MAPPER = (rs, i) -> new Node(
            rs.getLong("id"), rs.getLong("owner_id"),
            (Long) rs.getObject("parent_id"),
            rs.getString("name"), rs.getString("path"), rs.getString("kind"),
            rs.getLong("size"), rs.getString("mime"), rs.getString("sha256"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static String join(String parentPath, String name) {
        if ("/".equals(parentPath)) return "/" + name;
        return parentPath + "/" + name;
    }

    public Node rootOf(long ownerId) {
        List<Node> rows = jdbc.query(
                "select * from drv_node where owner_id=? and parent_id is null", MAPPER, ownerId);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND, "根目录缺失");
        return rows.get(0);
    }

    public Node byId(long ownerId, long id) {
        List<Node> rows = jdbc.query(
                "select * from drv_node where owner_id=? and id=?", MAPPER, ownerId, id);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    public Node byPath(long ownerId, String path) {
        List<Node> rows = jdbc.query(
                "select * from drv_node where owner_id=? and path=?", MAPPER, ownerId, path);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    /** 列目录：parentId 为 null 表示根。不看回收站。根节点自身不列。 */
    public List<Node> list(long ownerId, Long parentId) {
        return list(ownerId, parentId, "name", "asc");
    }

    /**
     * 带排序的列目录。orderBy 只接受白名单，防止 SQL 注入。
     * 文件夹永远排前面，这是网盘的基本预期。
     */
    public List<Node> list(long ownerId, Long parentId, String orderBy, String direction) {
        Long pid = (parentId == null) ? rootOf(ownerId).id() : parentId;
        String col = switch (orderBy == null ? "name" : orderBy) {
            case "size" -> "size";
            case "updatedAt" -> "updated_at";
            default -> "name";
        };
        String dir = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
        return jdbc.query("select * from drv_node where owner_id=? and parent_id=? " +
                "and status <> 'TRASHED' order by kind desc, " + col + " " + dir,
                MAPPER, ownerId, pid);
    }

    /**
     * 全局搜索，走 Lucene 索引（支持中文子串、拼音、首字母、拼写容错、相关度排序）。
     *
     * <p>取代了原来的 {@code lower(name) like '%kw%'}：
     * LIKE 不能打拼音、不能容错、也没有相关度（只能按时间排）。
     * 回收站不参与 —— 索引里本来就不放 TRASHED 的节点。
     */
    public List<Node> search(long ownerId, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        if (searchSync == null) return List.of();

        List<Node> out = new ArrayList<>();
        for (games.beiming.drive.search.SearchIndex.Hit h :
                searchSync.search(String.valueOf(ownerId), keyword, limit)) {
            Object idObj = h.doc().payload().get("id");
            if (idObj == null) continue;
            long id = Long.parseLong(String.valueOf(idObj));
            // 回库取当前状态：索引可能比库慢一拍（极小概率），以库为准
            try {
                out.add(byId(ownerId, id));
            } catch (RuntimeException ignored) {
                // 索引里有、库里已没（刚删），跳过并顺手清掉
                indexRemove(id);
            }
        }
        return out;
    }

    @Transactional
    public Node mkdir(long ownerId, Long parentId, String name) {
        Node parent = parentId == null ? rootOf(ownerId) : byId(ownerId, parentId);
        if (!parent.isDir()) throw new DriveException(ErrorCode.BAD_REQUEST, "父节点不是目录");
        String path = join(parent.path(), name);
        Integer dup = jdbc.queryForObject(
                "select count(*) from drv_node where owner_id=? and path=? and status<>'TRASHED'",
                Integer.class, ownerId, path);
        if (dup != null && dup > 0) throw new DriveException(ErrorCode.CONFLICT);

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into drv_node(owner_id,parent_id,name,path,kind) values (?,?,?,?,'dir')",
                    new String[]{"id"});
            ps.setLong(1, ownerId);
            ps.setLong(2, parent.id());
            ps.setString(3, name);
            ps.setString(4, path);
            return ps;
        }, kh);
        Node created = byId(ownerId, java.util.Objects.requireNonNull(kh.getKey()).longValue());
        indexPut(created);
        return created;
    }

    /** 建文件条目（内容已落到 blob 之后调用）。同名已存在则覆盖内容。 */
    @Transactional
    public Node putFile(long ownerId, Long parentId, String name, String mime,
                        String sha256, long size) {
        Node parent = parentId == null ? rootOf(ownerId) : byId(ownerId, parentId);
        if (!parent.isDir()) throw new DriveException(ErrorCode.BAD_REQUEST, "父节点不是目录");
        String path = join(parent.path(), name);

        List<Node> existing = jdbc.query(
                "select * from drv_node where owner_id=? and path=? and status<>'TRASHED'",
                MAPPER, ownerId, path);
        if (!existing.isEmpty()) {
            Node old = existing.get(0);
            if (old.isDir()) throw new DriveException(ErrorCode.CONFLICT, "同名目录已存在");
            long delta = size - old.size();
            adjustQuota(ownerId, delta);
            jdbc.update("update drv_node set sha256=?, size=?, mime=?, status='READY', " +
                            "updated_at=now() where id=?",
                    sha256, size, mime, old.id());
            Node updated = byId(ownerId, old.id());
            indexPut(updated);
            return updated;
        }

        adjustQuota(ownerId, size);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into drv_node(owner_id,parent_id,name,path,kind,size,mime,sha256,status) " +
                            "values (?,?,?,?,'file',?,?,?,'READY')",
                    new String[]{"id"});
            ps.setLong(1, ownerId);
            ps.setLong(2, parent.id());
            ps.setString(3, name);
            ps.setString(4, path);
            ps.setLong(5, size);
            ps.setString(6, mime);
            ps.setString(7, sha256);
            return ps;
        }, kh);
        Node created = byId(ownerId, java.util.Objects.requireNonNull(kh.getKey()).longValue());
        indexPut(created);
        return created;
    }

    private void adjustQuota(long ownerId, long delta) {
        if (delta == 0) return;
        if (delta > 0) {
            Integer ok = jdbc.queryForObject(
                    "select count(*) from drv_user where id=? and storage_used + ? <= storage_quota",
                    Integer.class, ownerId, delta);
            if (ok == null || ok == 0) throw new DriveException(ErrorCode.QUOTA_EXCEEDED);
        }
        jdbc.update("update drv_user set storage_used = greatest(0, storage_used + ?) where id=?",
                delta, ownerId);
    }

    /** 移入回收站（目录级联）。 */
    @Transactional
    public void trash(long ownerId, long id) {
        Node n = byId(ownerId, id);
        if (n.parentId() == null) throw new DriveException(ErrorCode.BAD_REQUEST, "不能删除根目录");
        jdbc.update("update drv_node set status='TRASHED', trashed_at=now() " +
                "where owner_id=? and (id=? or path like ?)", ownerId, id, n.path() + "/%");
        adjustQuota(ownerId, -(subtreeBytes(ownerId, id)));
        // 回收站里的不进索引，整棵子树都摘掉
        indexRemove(id);
        for (Node d : jdbc.query(
                "select * from drv_node where owner_id=? and path like ?",
                MAPPER, ownerId, n.path() + "/%")) {
            indexRemove(d.id());
        }
    }

    private long subtreeBytes(long ownerId, long id) {
        Long v = jdbc.queryForObject(
                "select coalesce(sum(size),0) from drv_node where owner_id=? " +
                        "and kind='file' and (id=? or path like (select path from drv_node where id=?) || '/%')",
                Long.class, ownerId, id, id);
        return v == null ? 0L : v;
    }

    @Transactional
    public void restore(long ownerId, long id) {
        Node n = byId(ownerId, id);
        jdbc.update("update drv_node set status='READY', trashed_at=null " +
                "where owner_id=? and (id=? or path like ?)", ownerId, id, n.path() + "/%");
        adjustQuota(ownerId, subtreeBytes(ownerId, id));
        // 恢复后重新进索引
        for (Node d : jdbc.query(
                "select * from drv_node where owner_id=? and (id=? or path like ?)",
                MAPPER, ownerId, id, n.path() + "/%")) {
            indexPut(d);
        }
    }

    /** 彻底删除（含 blob 引用计数不管，交给后续 GC 任务）。 */
    @Transactional
    public void purge(long ownerId, long id) {
        Node n = byId(ownerId, id);
        // 先摘索引再删数据：索引里留着已不存在的条目，搜到了点进去会 404
        indexRemove(id);
        for (Node d : jdbc.query(
                "select * from drv_node where owner_id=? and path like ?",
                MAPPER, ownerId, n.path() + "/%")) {
            indexRemove(d.id());
        }
        jdbc.update("delete from drv_node where owner_id=? and (id=? or path like ?)",
                ownerId, id, n.path() + "/%");
    }

    public List<Node> trashList(long ownerId) {
        return jdbc.query("select * from drv_node where owner_id=? and status='TRASHED' " +
                "and parent_id is not null order by trashed_at desc", MAPPER, ownerId);
    }

    /** 改名或移动：级联更新子树 path。 */
    @Transactional
    public Node rename(long ownerId, long id, String newName) {
        Node n = byId(ownerId, id);
        if (n.parentId() == null) throw new DriveException(ErrorCode.BAD_REQUEST, "不能重命名根目录");
        Node parent = byId(ownerId, n.parentId());
        String newPath = join(parent.path(), newName);

        Integer dup = jdbc.queryForObject(
                "select count(*) from drv_node where owner_id=? and path=? and id<>? and status<>'TRASHED'",
                Integer.class, ownerId, newPath, id);
        if (dup != null && dup > 0) throw new DriveException(ErrorCode.CONFLICT);

        // 先改子树里深层节点的 path，再改自己，避免前缀互相覆盖
        List<Node> descendants = jdbc.query(
                "select * from drv_node where owner_id=? and path like ? order by length(path) desc",
                MAPPER, ownerId, n.path() + "/%");
        List<Object[]> batch = new ArrayList<>();
        for (Node d : descendants) {
            batch.add(new Object[]{newPath + d.path().substring(n.path().length()), d.id()});
        }
        jdbc.batchUpdate("update drv_node set path=?, updated_at=now() where id=?", batch);
        jdbc.update("update drv_node set name=?, path=?, updated_at=now() where id=?",
                newName, newPath, id);
        Node renamed = byId(ownerId, id);
        // 改名会连带子树 path 全部变化，整棵子树重灌索引（path 也在可检索字段里）
        indexPut(renamed);
        for (Node d : jdbc.query(
                "select * from drv_node where owner_id=? and path like ? and status<>'TRASHED'",
                MAPPER, ownerId, newPath + "/%")) {
            indexPut(d);
        }
        return renamed;
    }

    /**
     * 把节点移到另一个目录（保持自己的名字）。
     *
     * 注意：不能直接复用 rename(id, n.name())——rename 是按「节点当前的 parent」拼新路径的，
     * 那样拼出来的还是旧路径，移动等于没动。必须显式用目标目录的 path 拼。
     * 子树 path 要级联更新，并且先改深层再改自己，避免前缀互相覆盖。
     */
    @Transactional
    public Node move(long ownerId, long id, long targetParentId) {
        Node n = byId(ownerId, id);
        if (n.parentId() == null) throw new DriveException(ErrorCode.BAD_REQUEST, "不能移动根目录");

        Node target = byId(ownerId, targetParentId);
        if (!target.isDir()) throw new DriveException(ErrorCode.BAD_REQUEST, "目标不是目录");
        if (n.id() == target.id()) throw new DriveException(ErrorCode.BAD_REQUEST, "不能移动到自身");
        // 目标在自身子树内 = 会形成环
        if (target.path().equals(n.path()) || target.path().startsWith(n.path() + "/")) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "不能移动到自己的子目录");
        }

        String newPath = join(target.path(), n.name());
        Integer dup = jdbc.queryForObject(
                "select count(*) from drv_node where owner_id=? and path=? and id<>? and status<>'TRASHED'",
                Integer.class, ownerId, newPath, id);
        if (dup != null && dup > 0) {
            throw new DriveException(ErrorCode.CONFLICT, "目标目录已有同名条目");
        }

        List<Node> descendants = jdbc.query(
                "select * from drv_node where owner_id=? and path like ? order by length(path) desc",
                MAPPER, ownerId, n.path() + "/%");
        List<Object[]> batch = new ArrayList<>();
        for (Node d : descendants) {
            batch.add(new Object[]{newPath + d.path().substring(n.path().length()), d.id()});
        }
        jdbc.batchUpdate("update drv_node set path=?, updated_at=now() where id=?", batch);
        jdbc.update("update drv_node set parent_id=?, path=?, updated_at=now() where id=?",
                target.id(), newPath, id);
        Node moved = byId(ownerId, id);
        indexPut(moved);
        for (Node d : jdbc.query(
                "select * from drv_node where owner_id=? and path like ? and status<>'TRASHED'",
                MAPPER, ownerId, newPath + "/%")) {
            indexPut(d);
        }
        return moved;
    }

    /** 存储构成：按类型分组 + 回收站占用。给「存储管理」页用。
     *
     *  分组口径按扩展名，不按 MIME ——
     *  MIME 在浏览器上传时会带一堆 application/octet-stream，
     *  扩展名反而更稳，也更好跟用户解释（「图片 3.2 GB」）。
     *  回收站单独算：它照样占着 blob，不清掉就不算释放空间。*/
    public record Bucket(String key, String label, int files, long bytes) {
    }

    /** 大文件条目：给「存储空间」页的「最占地方的文件」列表。
     *  只用 name/path/size，不带 sha256 之类的内部字段。 */
    public record BigFile(long id, String name, String path, long size, String bucket) {
    }

    public record StorageBreakdown(long total, long quota, long used, long trashBytes,
                                   int trashFiles, List<Bucket> buckets, List<BigFile> bigFiles) {
    }

    private static final Map<String, String[]> TYPE_MAP = new LinkedHashMap<>();

    static {
        TYPE_MAP.put("image", new String[]{"图片", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "avif", "heic"});
        TYPE_MAP.put("video", new String[]{"视频", "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "rmvb"});
        TYPE_MAP.put("audio", new String[]{"音频", "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "ape"});
        TYPE_MAP.put("doc", new String[]{"文档", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md",
                "rtf", "csv", "odt", "ods", "odp", "epub"});
        TYPE_MAP.put("archive", new String[]{"压缩包", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"});
        TYPE_MAP.put("code", new String[]{"代码", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "cs",
                "php", "rb", "sh", "bat", "ps1", "html", "css", "json", "xml", "yml", "yaml", "sql"});
    }

    private static String bucketOf(String name) {
        if (name == null) return "other";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "other";
        String ext = name.substring(dot + 1).toLowerCase();
        for (Map.Entry<String, String[]> e : TYPE_MAP.entrySet()) {
            for (int i = 1; i < e.getValue().length; i++) {
                if (e.getValue()[i].equals(ext)) return e.getKey();
            }
        }
        return "other";
    }

    public StorageBreakdown storageBreakdown(long ownerId) {
        Long quotaObj = jdbc.queryForObject(
                "select storage_quota from drv_user where id=?", Long.class, ownerId);
        long quota = quotaObj == null ? 0L : quotaObj;
        List<Node> all = jdbc.query(
                "select * from drv_node where owner_id=? and kind='file'", MAPPER, ownerId);
        Map<String, long[]> agg = new LinkedHashMap<>();   // key -> [files, bytes]
        Map<String, long[]> trash = new LinkedHashMap<>();
        for (Node n : all) {
            String k = bucketOf(n.name());
            boolean trashed = "TRASHED".equals(n.status());
            Map<String, long[]> target = trashed ? trash : agg;
            long[] a = target.computeIfAbsent(k, x -> new long[2]);
            a[0] += 1;
            a[1] += n.size();
        }
        // 所有出现的类型都要列出来（含回收站里的），否则用户看不到自己在回收站里的图片
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(agg.keySet());
        keys.addAll(trash.keySet());
        List<Bucket> buckets = new ArrayList<>();
        for (String k : TYPE_MAP.keySet()) {
            if (!keys.contains(k)) continue;
            long[] a = agg.getOrDefault(k, new long[2]);
            buckets.add(new Bucket(k, TYPE_MAP.get(k)[0], (int) a[0], a[1]));
        }
        if (keys.contains("other")) {
            long[] a = agg.getOrDefault("other", new long[2]);
            buckets.add(new Bucket("other", "其他", (int) a[0], a[1]));
        }
        long used = 0;
        for (Bucket b : buckets) used += b.bytes();
        long trashBytes = 0;
        int trashFiles = 0;
        for (long[] a : trash.values()) {
            trashBytes += a[1];
            trashFiles += (int) a[0];
        }
        // 最占地方的 8 个文件：只算未删的，回收站里的单独算过总量了，
        // 混进来会让「点它去清」的引导变糊。
        List<BigFile> big = new ArrayList<>();
        for (Node n : jdbc.query(
                "select * from drv_node where owner_id=? and kind='file' and status<>'TRASHED' "
                        + "order by size desc limit 8", MAPPER, ownerId)) {
            if (n.size() <= 0) continue;
            big.add(new BigFile(n.id(), n.name(), n.path(), n.size(), bucketOf(n.name())));
        }
        return new StorageBreakdown(quota, quota, used, trashBytes, trashFiles, buckets, big);
    }

    /** 递归统计：目录数/文件数/字节。用于验收。 */
    public record Stats(int dirs, int files, long bytes) {
    }

    public Stats stats(long ownerId, Long parentId) {
        List<Node> all = jdbc.query(
                "select * from drv_node where owner_id=? and status<>'TRASHED'", MAPPER, ownerId);
        // 从 parentId 出发按 path 前缀归属
        String base;
        if (parentId == null) {
            base = "/";
        } else {
            base = byId(ownerId, parentId).path();
        }
        int dirs = 0, files = 0;
        long bytes = 0;
        for (Node n : all) {
            if (n.parentId() == null) continue;
            boolean inside = "/".equals(base) || n.path().startsWith(base + "/");
            if (!inside) continue;
            if (n.isDir()) dirs++;
            else {
                files++;
                bytes += n.size();
            }
        }
        return new Stats(dirs, files, bytes);
    }
}