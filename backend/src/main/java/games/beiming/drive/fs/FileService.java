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
 *
 * <h3>2026-11-28 起的模型变化</h3>
 * 以前是「每人一棵私有树」，所有查询都带 {@code owner_id=?}，别人看不见我的文件。
 * 现在是**共享目录树**：只有一棵树，所有人共用。
 * {@code owner_id} 于是从「隔离依据」降级成「谁上传的/谁建的」，只用于展示归属，
 * 不再用于隔离 —— 隔离改由 {@link NodePermissionService} 按 ACL 判定。
 *
 * 所以本类里方法参数 {@code ownerId} 一律改叫 {@code userId}：
 * 它现在的意思是「**当前操作的人**」，不是「所有者」。
 * 保留位置和顺序的话调用方一处都不用改，但语义必须摆正，
 * 否则下一个人读代码会以为这里还在做归属校验。
 */
@Service
public class FileService {

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;
    private final NodePermissionService perms;
    /** 搜索索引同步。用 setter 注入，因为 FsSearchSync 依赖 FileService.Node 的构造，直接构造注入会成环。 */
    private FsSearchSync searchSync;

    public FileService(JdbcTemplate jdbc, BlobStore blobs, NodePermissionService perms) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.perms = perms;
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
                       String status, Instant createdAt, Instant updatedAt, Instant trashedAt,
                       String originalPath) {

        public boolean isDir() {
            return "dir".equals(kind);
        }
    }

    /** 行映射。公开给 SearchBootstrap 复用 —— 节点字段加一个，两处手写构造就得改两处，
     *  漏一处就是编译错。共用一份映射，加字段只改这一个地方。 */
    public static final RowMapper<Node> MAPPER = (rs, i) -> new Node(
            rs.getLong("id"), rs.getLong("owner_id"),
            (Long) rs.getObject("parent_id"),
            rs.getString("name"), rs.getString("path"), rs.getString("kind"),
            rs.getLong("size"), rs.getString("mime"), rs.getString("sha256"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("trashed_at") == null ? null : rs.getTimestamp("trashed_at").toInstant(),
            rs.getString("original_path"));

    private static String join(String parentPath, String name) {
        if ("/".equals(parentPath)) return "/" + name;
        return parentPath + "/" + name;
    }

    /**
     * 「根目录」现在对所有人都是同一个 —— 全站唯一那棵树的根。
     *
     * 参数 userId 只用来判权限，不再用来选根：以前是「找我的根」，
     * 现在是「大家的根是同一个，你给我看有没有浏览权」。
     */
    public Node rootOf(long userId) {
        List<Node> rows = jdbc.query(
                "select * from drv_node where is_root limit 1", MAPPER);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND, "根目录缺失");
        Node root = rows.get(0);
        perms.require(userId, root.id(), NodePermissionService.Right.BROWSE, isAdmin(userId));
        return root;
    }

    /**
     * 按 id 取节点，顺带做浏览权校验。
     *
     * 这里把「是不是你的」换成了「你有没有权看」：
     * 以前 owner_id 对不上就 404，现在没授权也是 404 —— 仍然不告诉对方
     * 「这个文件存在但你无权看」，因为那本身就是信息泄露。
     */
    public Node byId(long userId, long id) {
        List<Node> rows = jdbc.query(
                "select * from drv_node where id=?", MAPPER, id);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND);
        Node n = rows.get(0);
        if (!perms.can(userId, id, NodePermissionService.Right.BROWSE, isAdmin(userId))) {
            throw new DriveException(ErrorCode.NOT_FOUND);
        }
        return n;
    }

    /** 取节点但**不校验浏览权**。给内部逻辑用（改权限、算配额、回收站），别在接口层直接用 */
    public Node byIdRaw(long id) {
        List<Node> rows = jdbc.query("select * from drv_node where id=?", MAPPER, id);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    /**
     * 按路径取。新模型里 path 是全站唯一的绝对路径，所以不带 owner_id 条件 ——
     * 带了反而会漏（别人传的文件 owner_id 不是我，但我有权限就该能取到）。
     */
    public Node byPath(long userId, String path) {
        List<Node> rows = jdbc.query(
                "select * from drv_node where path=? and status <> 'TRASHED'", MAPPER, path);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND);
        Node n = rows.get(0);
        if (!perms.can(userId, n.id(), NodePermissionService.Right.BROWSE, isAdmin(userId))) {
            throw new DriveException(ErrorCode.NOT_FOUND);
        }
        return n;
    }

    /** 当前用户是不是管理员（走 ACL 旁路）。每次查一次库，量很小 */
    private boolean isAdmin(long userId) {
        try {
            String role = jdbc.queryForObject(
                    "select role from drv_user where id=?", String.class, userId);
            return games.beiming.drive.auth.Role.of(role).isAdmin();
        } catch (Exception e) {
            return false;
        }
    }

    /** 列目录：parentId 为 null 表示根。不看回收站。根节点自身不列。 */
    public List<Node> list(long userId, Long parentId) {
        return list(userId, parentId, "name", "asc");
    }

    /**
     * 带排序的列目录。orderBy 只接受白名单，防止 SQL 注入。
     * 文件夹永远排前面，这是网盘的基本预期。
     *
     * 新的地方：不再按 owner_id 过滤（共享树里大家都是同一棵树），
     * 改成「列出这一层里我有浏览权的那些」。
     */
    public List<Node> list(long userId, Long parentId, String orderBy, String direction) {
        Long pid = (parentId == null) ? rootOf(userId).id() : parentId;
        // 没权看这个目录就直接拒，别返回空列表 —— 空列表会让人以为「这里本来就没东西」
        perms.require(userId, pid, NodePermissionService.Right.BROWSE, isAdmin(userId));

        String col = switch (orderBy == null ? "name" : orderBy) {
            case "size" -> "size";
            case "updatedAt" -> "updated_at";
            default -> "name";
        };
        String dir = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
        List<Node> all = jdbc.query(
                "select * from drv_node where parent_id=? and status <> 'TRASHED' " +
                "order by kind desc, " + col + " " + dir, MAPPER, pid);
        if (all.isEmpty()) return all;

        // 逐个看权限。目录一屏几十项，批量筛一次查完再过滤。
        boolean admin = isAdmin(userId);
        if (admin) return all;
        Map<Long, NodePermissionService.Perm> ok =
                perms.effectiveBatch(userId, all.stream().map(Node::id).toList(), false);
        return all.stream().filter(n -> ok.getOrDefault(n.id(), NodePermissionService.Perm.NONE).browse())
                  .toList();
    }

    /**
     * 全局搜索，走 Lucene 索引（支持中文子串、拼音、首字母、拼写容错、相关度排序）。
     *
     * <p>取代了原来的 {@code lower(name) like '%kw%'}：
     * LIKE 不能打拼音、不能容错、也没有相关度（只能按时间排）。
     * 回收站不参与 —— 索引里本来就不放 TRASHED 的节点。
     */
    public List<Node> search(long userId, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        if (searchSync == null) return List.of();

        // 共享树之后，索引不再按人隔离（scope 传 null = 搜全站），
        // 能不能看见改由 ACL 判定 —— 搜得到不等于看得到。
        //
        // 注意参数顺序是 (ownerScope, keyword, limit)：scope 在前。
        // 写反会变成 scope=关键词、keyword=null → 永远返回空，
        // 而且不报错，只是「搜什么都搜不到」，极难查（实测踩过一次）。
        boolean admin = isAdmin(userId);
        List<Node> out = new ArrayList<>();
        for (games.beiming.drive.search.SearchIndex.Hit h :
                searchSync.search(null, keyword, limit)) {
            Object idObj = h.doc().payload().get("id");
            if (idObj == null) continue;
            long id = Long.parseLong(String.valueOf(idObj));

            // 回库取当前状态：索引可能比库慢一拍，以库为准
            Node n;
            try {
                n = byIdRaw(id);
            } catch (RuntimeException gone) {
                // 真的没了（刚被彻底删除）→ 顺手清掉索引
                indexRemove(id);
                continue;
            }

            // 这里**不能用 byId 的 try/catch 来判断**：它把「无权看」也报成 404，
            // 一旦按异常分支处理就会把别人没权限的文件从索引里删掉 —— 那是删数据，
            // 不是过滤结果。所以先确认节点还在，再单独问权限。
            if (perms.can(userId, id, NodePermissionService.Right.BROWSE, admin)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 找一个没被占用的路径。
     *
     * <p>现在唯一的使用场景是**恢复**：文件在回收站里待着的时候，用户可能又在原位置
     * 建了个同名条目。恢复时不能撞 {@code uq_drv_node_path}，也不该把那新条目删掉，
     * 所以给回来的那个换个名字（`报告 (1).docx`），和文件管理器的行为一致。
     *
     * <p>新建/改名/移动**不需要**调用它 —— 回收站里的条目现在住在
     * {@code /.trash/<id>} 独立命名空间（见 {@link #trash}），不会再占着可见路径。
     *
     * @param excludeId 要把自己排除在「占用」之外时传自己的 id；新建传 null
     */
    private String freePath(long userId, Node parent, String name, Long excludeId) {
        String candidate = name;
        int seq = 1;
        while (true) {
            String path = join(parent.path(), candidate);
            // 共享树里 path 全局唯一，所以判重**不带 owner_id** ——
            // 别人已经在同一位置放了同名文件，我也得让路（以前各人一棵树才不用管）。
            Integer dup = jdbc.queryForObject(
                    "select count(*) from drv_node where path=? and (?::bigint is null or id<>?)",
                    Integer.class, path, excludeId, excludeId);
            if (dup == null || dup == 0) return path;
            int dot = name.lastIndexOf('.');
            // 带扩展名时把序号插在扩展名前面（报告 (1).pdf 而不是 报告.pdf (1)）
            if (dot > 0 && !name.endsWith(".")) {
                candidate = name.substring(0, dot) + " (" + seq++ + ")" + name.substring(dot);
            } else {
                candidate = name + " (" + seq++ + ")";
            }
            if (seq > 999) throw new DriveException(ErrorCode.CONFLICT, "同名条目太多了");
        }
    }

    @Transactional
    public Node mkdir(long userId, Long parentId, String name) {
        Node parent = parentId == null ? rootOf(userId) : byId(userId, parentId);
        if (!parent.isDir()) throw new DriveException(ErrorCode.BAD_REQUEST, "父节点不是目录");

        // 花蝉 2026-11-28 定的规则：**文件夹只能管理员建**。
        // 普通用户只能往管理员建好的目录里传文件。
        if (!isAdmin(userId)) {
            throw new DriveException(ErrorCode.FORBIDDEN, "只有管理员能新建文件夹");
        }

        String path = join(parent.path(), name);
        // 判重不带 owner_id：共享树里 path 全局唯一
        Integer dup = jdbc.queryForObject(
                "select count(*) from drv_node where path=? and status <> 'TRASHED'",
                Integer.class, path);
        if (dup != null && dup > 0) throw new DriveException(ErrorCode.CONFLICT);

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into drv_node(owner_id,parent_id,name,path,kind) values (?,?,?,?,'dir')",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setLong(2, parent.id());
            ps.setString(3, name);
            ps.setString(4, path);
            return ps;
        }, kh);
        Node created = byId(userId, java.util.Objects.requireNonNull(kh.getKey()).longValue());
        // 建者本人全权：不给这条的话，建者会继承上级规则，可能连自己刚建的都动不了。
        //
        // **但不要给「其他人」写死规则**：管理员建「公共资料」这种目录本意就是给大家用的，
        // 硬写 everyone=NONE 会让它变成只有管理员能进（实测踩过：普通用户直接 404）。
        // 其他人不写规则 = 自然继承上级 —— 上级是公共区就公共，是个人目录就关门。
        // 要收紧某个目录，由管理员在权限面板里显式设，不由 mkdir 替他决定。
        perms.grant(created.id(), userId, NodePermissionService.Perm.ALL, userId);
        indexPut(created);
        return created;
    }

    /** 建文件条目（内容已落到 blob 之后调用）。同名可见文件则覆盖内容。 */
    @Transactional
    public Node putFile(long ownerId, Long parentId, String name, String mime,
                        String sha256, long size) {
        Node parent = parentId == null ? rootOf(ownerId) : byId(ownerId, parentId);
        if (!parent.isDir()) throw new DriveException(ErrorCode.BAD_REQUEST, "父节点不是目录");

        // 共享树里「在别人的目录里传东西」是常态，必须校验上传权。
        // 花蝉定的规则：文件夹只有管理员能建，但普通用户可以在
        // 管理员开了上传权的目录里传文件。
        perms.require(ownerId, parent.id(), NodePermissionService.Right.UPLOAD, isAdmin(ownerId));

        String path = join(parent.path(), name);

        List<Node> existing = jdbc.query(
            "select * from drv_node where path=? and status<>'TRASHED'",
            MAPPER, path);
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

    /**
     * 移入回收站（目录级联）。
     *
     * <p>关键在于**搬家**：把 path 从 `/<原名>` 改成 `/.trash/<id>`，
     * 子树跟着换成 `/.trash/<id>/…`。
     *
     * <p>为什么要搬：{@code uq_drv_node_path} 不区分 status，回收站里的条目
     * 如果还占着 `/<原名>`，用户删掉再上传一个同名文件就会撞唯一约束 —— 500。
     * （这个 bug 是本次实测发现的）。给新建让路、自动加序号只会把内部约束
     * 漏成「报告 (1).docx」这种莫名的名字。搬进独立命名空间，两边就再也不打架。
     *
     * <p>原路径记进 {@code original_path}，恢复时照着搬回去。
     */
    @Transactional
    public void trash(long ownerId, long id) {
        Node n = byId(ownerId, id);
        if (n.parentId() == null) throw new DriveException(ErrorCode.BAD_REQUEST, "不能删除根目录");

        String trashPath = trashRootOf(id);
        // 配额必须**搬之前**算：subtreeBytes 按 path 前缀统计，一搬就找不到了
        long bytes = subtreeBytes(ownerId, id);

        // 先子树后自己，理由同 rename：避免前缀互相覆盖
        List<Node> sub = jdbc.query(
                "select * from drv_node where path like ? order by length(path) desc",
                MAPPER, n.path() + "/%");
        // 子树必须跟着标 TRASHED —— 只搬 path 不搬 status 的话，
        // 里面的文件还留着 READY，既是「在回收站里」又是「活文件」，两边都算不清。
        List<Object[]> batch = new ArrayList<>();
        for (Node d : sub) {
            batch.add(new Object[]{
                    d.status().equals("TRASHED")
                            ? d.path()
                            : trashPath + d.path().substring(n.path().length()),
                    d.path(),   // 子树节点也要记住自己的原路径
                    d.id()});
        }
        jdbc.batchUpdate("update drv_node set path=?, original_path=coalesce(original_path, ?), "
                + "status='TRASHED', trashed_at=now() where id=?", batch);
        jdbc.update("update drv_node set status='TRASHED', trashed_at=now(), path=?, "
                + "original_path=coalesce(original_path, ?) where id=?", trashPath, n.path(), id);

        adjustQuota(ownerId, -bytes);
        // 回收站里的不进索引，整棵子树都摘掉
        indexRemove(id);
        for (Node d : sub) indexRemove(d.id());
    }

    /** 回收站里的位置：`/.trash/<id>`。用 id 而不是原名，同名条目互不干扰。 */
    private static String trashRootOf(long id) {
        return "/.trash/" + id;
    }

    private long subtreeBytes(long ownerId, long id) {
        Long v = jdbc.queryForObject(
                "select coalesce(sum(size),0) from drv_node " +
                        "where kind='file' and (id=? or path like (select path from drv_node where id=?) || '/%')",
                Long.class, id, id);
        return v == null ? 0L : v;
    }

    /**
     * 从回收站恢复。
     *
     * <p>{@code (owner_id, path)} 是全局唯一约束，所以恢复时如果原位置已经被
     * 同名的东西占了（删掉之后又重新建了一个），直接 update 会撞唯一键、抛 500。
     * 这里改成自动换个名字恢复：优先叫「原名 (恢复)」，还占着就加序号。
     * 用户点「恢复」是想把东西拿回来，不是想看一个数据库约束报错。
     */
    @Transactional
    public Node restore(long ownerId, long id) {
        Node n = byId(ownerId, id);
        if (!"TRASHED".equals(n.status())) {
            throw new DriveException(ErrorCode.STATE_CONFLICT, "这一项不在回收站里");
        }

        // 只允许恢复**顶层**条目。回收站界面也只列顶层，所以这不会挡住正常操作；
        // 而单独恢复一个子条目会让它的父目录留在 /.trash 里，状态就说不清了。
        Integer parentTrashed = jdbc.queryForObject(
                "select count(*) from drv_node p join drv_node c on c.parent_id=p.id "
                        + "where c.id=? and p.status='TRASHED'",
                Integer.class, id);
        if (parentTrashed != null && parentTrashed > 0) {
            throw new DriveException(ErrorCode.STATE_CONFLICT, "请恢复它所在的那个文件夹");
        }

        // 照着 original_path 搬回可见空间。子树的原路径是它自己的 original_path，
        // 不需要按前缀推算 —— trash() 时逐个记下来了，这样父目录中途被改名也不会错位。
        List<Node> subtree = jdbc.query(
                "select * from drv_node where (id=? or path like ?) "
                        + "order by length(path) desc",
                MAPPER, id, n.path() + "/%");

        // 原位置可能已经被新的同名条目占了（删了之后又建了一个）—— 这时根节点换个名字，
        // 子树按「根节点的新名字」重新拼。只有这一层需要避让，用户看得懂。
        String targetPath = originalPathOf(subtree, id, n);
        String targetName = targetPath.substring(targetPath.lastIndexOf('/') + 1);
        Integer taken = jdbc.queryForObject(
                "select count(*) from drv_node where path=? and id<>?",
                Integer.class, targetPath, id);
        if (taken != null && taken > 0) {
            Node parent = byId(ownerId, n.parentId());
            String free = freePath(ownerId, parent, targetName, id);
            targetPath = free;
            targetName = free.substring(free.lastIndexOf('/') + 1);
        }

        // 逐条搬回去：用各自的 original_path 拼新路径（根节点用 targetPath 的名字）
        List<Object[]> moves = new ArrayList<>();
        for (Node d : subtree) {
            String dest;
            if (d.id() == id) {
                dest = targetPath;
            } else {
                String own = d.originalPath();
                if (own == null) continue;              // 理论上不该有，跳过别乱改
                dest = targetPath + own.substring(originalPathOf(subtree, id, n).length());
            }
            moves.add(new Object[]{dest, d.id()});
        }
        jdbc.batchUpdate("update drv_node set path=? where id=?", moves);
        // name 必须跟着 path 一起改：只改 path 的话，列表里还显示旧名字，
        // 「撞名.txt」的用户点重命名会看到「撞名 (1).txt」，自己都懵。
        jdbc.update("update drv_node set name=?, status='READY', trashed_at=null, "
                + "original_path=null where id=?", targetName, id);
        jdbc.update("update drv_node set status='READY', trashed_at=null, original_path=null "
                + "where path like ? and id<>?", targetPath + "/%", id);

        adjustQuota(ownerId, subtreeBytes(ownerId, id));
        // 恢复后重新进索引
        for (Node d : jdbc.query(
                "select * from drv_node where (id=? or path like ?)",
                MAPPER, id, targetPath + "/%")) {
            indexPut(d);
        }
        return byId(ownerId, id);
    }

    /** 取节点自己的原路径；历史数据可能没记（迁移前就进了回收站），退回当前路径。 */
    private static String originalPathOf(List<Node> subtree, long id, Node self) {
        for (Node d : subtree) {
            if (d.id() == id && d.originalPath() != null) return d.originalPath();
        }
        return self.originalPath() != null ? self.originalPath() : self.path();
    }

    /**
     * 彻底删除一条。
     *
     * <p>{@code purge} 不管调用者是用户手点还是定时任务，都走同一个入口 ——
     * 两条路径要是各写一份删除 SQL，早晚会有一边漏掉索引或者漏掉 blob。
     *
     * <p>返回被删掉的 sha256 列表，调用方拿去 {@link games.beiming.drive.storage.BlobGcService#releaseIfOrphan}
     * 释放磁盘。物理删除放在事务外做（见 FsPurgeService），所以这里只负责「记录没了」。
     */
    @Transactional
    public List<String> purge(long ownerId, long id) {
        Node n = byId(ownerId, id);
        // 先把要被删的文件 sha 收齐（含子树），删完就查不到了
        List<String> shas = jdbc.queryForList(
                "select sha256 from drv_node where kind='file' and sha256 is not null " +
                        "and (id=? or path like ?)",
                String.class, id, n.path() + "/%");

        // 先摘索引再删数据：索引里留着已不存在的条目，搜到了点进去会 404
        indexRemove(id);
        for (Node d : jdbc.query(
                "select * from drv_node where path like ?",
                MAPPER, n.path() + "/%")) {
            indexRemove(d.id());
        }
        jdbc.update("delete from drv_node where (id=? or path like ?)",
                id, n.path() + "/%");
        return shas;
    }

    /**
     * 回收站里「真正是顶层条目」的那些。
     *
     * <p>为什么不能直接把 status='TRASHED' 全列出来：删一个非空文件夹时，
     * {@link #trash} 按 path 前缀把整棵子树一起标成 TRASHED（这是对的，
     * 恢复时才能整棵回来）。但回收站界面要是把它们全列出来，
     * 用户看到的是一堆「文件夹 + 它里面每个文件」的重复条目，
     * 删了文件夹还会看到里面的文件单独躺在回收站里。
     *
     * <p>顶层判定：父节点不是 TRASHED（父节点为 null 的是根，根不会进回收站）。
     * 用 NOT EXISTS 而不是查出来在 Java 里过滤，是因为这条件本来就能由索引承担。
     */
    public List<Node> trashList(long ownerId) {
        return jdbc.query(
                "select n.* from drv_node n where n.owner_id=? and n.status='TRASHED' " +
                        "and n.parent_id is not null " +
                        "and not exists (select 1 from drv_node p " +
                        "                where p.id=n.parent_id and p.status='TRASHED') " +
                        "order by n.trashed_at desc",
                MAPPER, ownerId);
    }

    /** 回收站里「顶层条目」的个数与字节，给存储页和清理接口用。口径同 {@link #trashList}。
     *  retentionDays 一起带出来，前端展示「保留 N 天」不用自己写死一个数。 */
    public record TrashSummary(int entries, long bytes, int retentionDays) {
    }

    public TrashSummary trashSummary(long ownerId, int retentionDays) {
        List<Node> tops = trashList(ownerId);
        long bytes = 0;
        for (Node n : tops) bytes += subtreeBytes(ownerId, n.id());
        return new TrashSummary(tops.size(), bytes, retentionDays);
    }

    /** 改名或移动：级联更新子树 path。 */
    @Transactional
    public Node rename(long ownerId, long id, String newName) {
        Node n = byId(ownerId, id);
        if (n.parentId() == null) throw new DriveException(ErrorCode.BAD_REQUEST, "不能重命名根目录");
        Node parent = byId(ownerId, n.parentId());
        String newPath = join(parent.path(), newName);

        Integer dup = jdbc.queryForObject(
                "select count(*) from drv_node where path=? and id<>? and status<>'TRASHED'",
                Integer.class, newPath, id);
        if (dup != null && dup > 0) throw new DriveException(ErrorCode.CONFLICT);

        // 先改子树里深层节点的 path，再改自己，避免前缀互相覆盖
        List<Node> descendants = jdbc.query(
                "select * from drv_node where path like ? order by length(path) desc",
                MAPPER, n.path() + "/%");
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
                "select * from drv_node where path like ? and status<>'TRASHED'",
                MAPPER, newPath + "/%")) {
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
                "select count(*) from drv_node where path=? and id<>? and status<>'TRASHED'",
                Integer.class, newPath, id);
        if (dup != null && dup > 0) {
            throw new DriveException(ErrorCode.CONFLICT, "目标目录已有同名条目");
        }

        List<Node> descendants = jdbc.query(
                "select * from drv_node where path like ? order by length(path) desc",
                MAPPER, n.path() + "/%");
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
                "select * from drv_node where path like ? and status<>'TRASHED'",
                MAPPER, newPath + "/%")) {
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