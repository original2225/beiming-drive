package games.beiming.drive.fs;

import games.beiming.drive.auth.Role;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点权限判定。共享树模型下，「能不能碰这个节点」全靠这里回答。
 *
 * <h3>继承规则</h3>
 * 查一个节点有没有某项权限时，从该节点往上找，**取最近的一条授权**。
 * 最近的那条说了算，不再往上找 —— 这叫「就近覆盖」：
 * 根给了「可下载」，某个子目录单独设成「不可下载」，走进子目录就以子目录为准。
 *
 * 为什么用继承而不是给每个子节点摊开写：
 * 一个目录下面挂 200 个文件，改一次权限要改 201 行；继承只改目录那一行。
 *
 * <h3>为什么「就近覆盖」而不是「逐级取并集」</h3>
 * 并集语义下没法收紧权限：根给了可下载，子目录再怎么写都关不掉。
 * 覆盖语义才能表达「这个大区都能下，但这个子目录不行」。
 */
@Service
public class NodePermissionService {

    private final JdbcTemplate jdbc;

    public NodePermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 四项权限的最小集合 */
    public record Perm(boolean browse, boolean preview, boolean download, boolean upload) {
        public static final Perm NONE = new Perm(false, false, false, false);
        public static final Perm ALL = new Perm(true, true, true, true);

        public boolean any() { return browse || preview || download || upload; }
        public Perm and(Perm o) {
            return new Perm(browse && o.browse, preview && o.preview,
                            download && o.download, upload && o.upload);
        }
    }

    /**
     * 取某人对某节点的有效权限（带继承）。
     *
     * 实现用一个递归 CTE 一次查完，而不是在 Java 里循环查 parent：
     * 循环查是 N 次往返（目录越深越慢），而且每次都要判 null。
     *
     * @param userId  null = 未登录游客
     * @param adminBypass 管理员/超管直接全权，不走 ACL（否则管理员改不了自己刚建的目录）
     */
    public Perm effective(Long userId, long nodeId, boolean adminBypass) {
        if (adminBypass) return Perm.ALL;

        // 自己上传/自己建的东西，自己一定有全权。
        // 少了这条会出现荒唐情况：传进公共区的文件，上传者本人反而下载不了
        // （根节点是「可上传、不可下载」，别人传的文件就继承了这条 —— 实测踩到）。
        // 注意这里只看**这一层**的 owner，不看上级：别人的目录里我传的文件归我，
        // 那个目录本身仍归目录创建者，两者互不影响。
        String ownSql = "select owner_id from drv_node where id = ?";
        List<Long> owners = jdbc.query(ownSql, (rs, i) -> (Long) rs.getObject(1), nodeId);
        if (!owners.isEmpty() && userId != null && userId.equals(owners.get(0))) {
            return Perm.ALL;
        }

        // 从当前节点一路往上，取**第一次**遇到的那条授权。
        // ORDER BY depth 保证由近及远，LIMIT 1 就是「最近的那条说了算」。
        //
        // 注意 user 匹配：先找针对我个人的规则，同一节点没有个人规则才看「所有人」的规则。
        // 所以排序里 user_id 为 null 的排后面（3 比 2 大，用 coalesce 让它大）。
        String sql = """
            WITH RECURSIVE chain AS (
                SELECT id, parent_id, 0 AS depth FROM drv_node WHERE id = ?
                UNION ALL
                SELECT n.id, n.parent_id, c.depth + 1
                  FROM drv_node n JOIN chain c ON n.id = c.parent_id
            )
            SELECT a.can_browse, a.can_preview, a.can_download, a.can_upload
              FROM chain c
              JOIN drv_node_acl a ON a.node_id = c.id
             WHERE (a.user_id IS NOT DISTINCT FROM ?)
                OR a.user_id IS NULL
             ORDER BY c.depth, (CASE WHEN a.user_id IS NULL THEN 1 ELSE 0 END)
             LIMIT 1
            """;

        List<Perm> rows = jdbc.query(sql, (rs, i) -> new Perm(
                rs.getBoolean("can_browse"), rs.getBoolean("can_preview"),
                rs.getBoolean("can_download"), rs.getBoolean("can_upload")),
                nodeId, userId);

        // 一条规则都没有 = 什么都不能干（安全默认，不给人意外权限）
        return rows.isEmpty() ? Perm.NONE : rows.get(0);
    }

    /** 只要某一项，写起来更顺 */
    public boolean can(Long userId, long nodeId, Right right, boolean adminBypass) {
        Perm p = effective(userId, nodeId, adminBypass);
        return switch (right) {
            case BROWSE -> p.browse();
            case PREVIEW -> p.preview();
            case DOWNLOAD -> p.download();
            case UPLOAD -> p.upload();
        };
    }

    public enum Right { BROWSE, PREVIEW, DOWNLOAD, UPLOAD }

    /** 不满足就抛 —— 业务代码里少写 if */
    public void require(Long userId, long nodeId, Right right, boolean adminBypass) {
        if (!can(userId, nodeId, right, adminBypass)) {
            throw new DriveException(ErrorCode.FORBIDDEN,
                    switch (right) {
                        case BROWSE -> "你没有浏览这个目录的权限";
                        case PREVIEW -> "你没有预览这个文件的权限";
                        case DOWNLOAD -> "你没有下载这个文件的权限";
                        case UPLOAD -> "你没有往这个目录上传的权限";
                    });
        }
    }

    /**
     * 批量筛：这批节点里，哪些是我能浏览的。
     * 目录列表一屏几十个，逐个算 effective 是几十次递归查询 —— 太浪费。
     * 这里一次查完，再在 Java 里按继承关系推。
     */
    public Map<Long, Perm> effectiveBatch(Long userId, List<Long> nodeIds, boolean adminBypass) {
        Map<Long, Perm> out = new HashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) return out;
        if (adminBypass) {
            for (Long id : nodeIds) out.put(id, Perm.ALL);
            return out;
        }

        // 一把捞出这些节点及其所有祖先的授权规则
        String in = String.join(",", nodeIds.stream().map(String::valueOf).toList());
        String sql = """
            WITH RECURSIVE chain AS (
                SELECT id, parent_id FROM drv_node WHERE id IN (%s)
                UNION
                SELECT n.id, n.parent_id
                  FROM drv_node n JOIN chain c ON n.id = c.parent_id
            )
            SELECT c.id AS node_id, a.can_browse, a.can_preview,
                   a.can_download, a.can_upload, a.user_id
              FROM drv_node_acl a JOIN chain c ON c.id = a.node_id
             WHERE (a.user_id IS NOT DISTINCT FROM ?) OR a.user_id IS NULL
            """.formatted(in);

        Map<Long, Perm> personal = new HashMap<>();
        Map<Long, Perm> everyone = new HashMap<>();
        jdbc.query(sql, rs -> {
            Long nid = rs.getLong("node_id");
            Perm p = new Perm(rs.getBoolean("can_browse"), rs.getBoolean("can_preview"),
                              rs.getBoolean("can_download"), rs.getBoolean("can_upload"));
            if (rs.getObject("user_id") == null) everyone.put(nid, p);
            else personal.put(nid, p);
        }, userId);

        // 逐点按 parent 链找最近的一条
        for (Long id : nodeIds) {
            Perm found = null;
            Long cur = id;
            int guard = 0;
            while (cur != null && guard++ < 64) {          // guard 防 parent 成环
                Perm p = personal.get(cur);
                if (p == null) p = everyone.get(cur);
                if (p != null) { found = p; break; }
                cur = parentOf(cur);
            }
            // 与 effective() 保持同一条规则：自己名下的节点全权。
            // 两处必须一致，否则「列表里看得到、点进去 403」这种鬼事就会出现。
            Long own = ownerOf(id);
            if (userId != null && userId.equals(own)) {
                out.put(id, Perm.ALL);
            } else {
                out.put(id, found == null ? Perm.NONE : found);
            }
        }
        return out;
    }

    private Long ownerOf(long id) {
        List<Long> r = jdbc.query("select owner_id from drv_node where id = ?",
                (rs, i) -> (Long) rs.getObject(1), id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 单个 parent 查询。批量筛里逐点回退用，深度通常 < 10，可接受 */
    private Long parentOf(long id) {
        List<Long> r = jdbc.query(
                "select parent_id from drv_node where id = ?",
                (rs, i) -> (Long) rs.getObject(1), id);
        return r.isEmpty() ? null : r.get(0);
    }

    /* ==================== 授权（管理操作） ==================== */

    /**
     * 给某人对某节点授权。userId 传 null = 对所有人。
     * 这是管理操作，调用方必须先确认操作者有管理权。
     */
    public void grant(long nodeId, Long userId, Perm perm, long grantedBy) {
        // 同节点同人只能一条，用 upsert 而不是先查后写
        jdbc.update("""
            INSERT INTO drv_node_acl(node_id, user_id, can_browse, can_preview,
                                     can_download, can_upload, granted_by)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (node_id, user_id) WHERE user_id IS NOT NULL
            DO UPDATE SET can_browse = excluded.can_browse,
                          can_preview = excluded.can_preview,
                          can_download = excluded.can_download,
                          can_upload = excluded.can_upload,
                          granted_by = excluded.granted_by,
                          updated_at = now()
            """, nodeId, userId, perm.browse(), perm.preview(),
                perm.download(), perm.upload(), grantedBy);
    }

    /**
     * 给「所有人」授权。Postgres 的 ON CONFLICT 认不出 null，
     * 所以「所有人」这条单独先删后插（它本来就只有一个，删了不心疼）。
     */
    public void grantEveryone(long nodeId, Perm perm, long grantedBy) {
        jdbc.update("DELETE FROM drv_node_acl WHERE node_id = ? AND user_id IS NULL", nodeId);
        jdbc.update("""
            INSERT INTO drv_node_acl(node_id, user_id, can_browse, can_preview,
                                     can_download, can_upload, granted_by)
            VALUES (?, NULL, ?,?,?,?,?)
            """, nodeId, perm.browse(), perm.preview(),
                perm.download(), perm.upload(), grantedBy);
    }

    /** 收回某个人的授权（删掉这条，他就退回继承上级的规则） */
    public void revoke(long nodeId, Long userId) {
        if (userId == null) {
            jdbc.update("DELETE FROM drv_node_acl WHERE node_id = ? AND user_id IS NULL", nodeId);
        } else {
            jdbc.update("DELETE FROM drv_node_acl WHERE node_id = ? AND user_id = ?", nodeId, userId);
        }
    }

    public record AclRow(long id, Long userId, String nickname, Perm perm) {}

    /** 某节点上所有显式授权（不含继承来的），给管理界面的权限面板用 */
    public List<AclRow> aclOf(long nodeId) {
        return jdbc.query("""
            SELECT a.id, a.user_id, u.nickname,
                   a.can_browse, a.can_preview, a.can_download, a.can_upload
              FROM drv_node_acl a
              LEFT JOIN drv_user u ON u.id = a.user_id
             WHERE a.node_id = ?
             ORDER BY (a.user_id IS NULL) DESC, u.nickname
            """, (rs, i) -> new AclRow(
                rs.getLong("id"),
                rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                rs.getString("nickname"),
                new Perm(rs.getBoolean("can_browse"), rs.getBoolean("can_preview"),
                         rs.getBoolean("can_download"), rs.getBoolean("can_upload"))),
                nodeId);
    }

    /** 管理员判定：角色够就行，不看 ACL */
    public static boolean bypass(Role role) {
        return role != null && role.isAdmin();
    }
}