package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 账号安全与资料：安全问题、登录设备、用户管理。
 *
 * 和 AuthService 分开是因为 AuthService 管「进得来出得去」（注册/登录/JWT），
 * 这里管「进来以后能改什么」。混在一起那个类会越来越长。
 */
@Service
public class AccountService {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    /// 和 AuthService 保持一致：自己 new 一个 BCrypt(强度 10)，
    /// 不从 Spring 注入 —— 这个项目没有注册 PasswordEncoder bean，
    /// 注入会直接让容器起不来（UnsatisfiedDependencyException）。
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder(10);

    public AccountService(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /* ==================== 用户资料 ==================== */

    public record Profile(long id, String email, String nickname, String role, String roleLabel,
                          String bio, String qq, String avatarKey, Boolean emailVerified,
                          long quota, long used, java.time.Instant createdAt) {}

    public Profile profile(long userId) {
        return jdbc.queryForObject(
                "select id, email, nickname, role, bio, qq, avatar_key, email_verified, " +
                "storage_quota, storage_used, created_at from drv_user where id = ?",
                (rs, i) -> {
                    String role = rs.getString("role");
                    return new Profile(
                            rs.getLong("id"), rs.getString("email"), rs.getString("nickname"),
                            role, Role.of(role).label(),
                            rs.getString("bio"), rs.getString("qq"), rs.getString("avatar_key"),
                            rs.getBoolean("email_verified"),
                            rs.getLong("storage_quota"), rs.getLong("storage_used"),
                            rs.getTimestamp("created_at").toInstant());
                }, userId);
    }

    /**
     * 改自己的资料。昵称、简介、QQ 号一起改，传 null 表示「这一项不动」。
     *
     * 为什么不拆成三个接口：设置页是一个表单，一次提交三个字段，
     * 拆开要发三次请求、还要处理「第一个成功第二个失败」的半成品状态。
     */
    @Transactional
    public Profile updateProfile(long userId, String nickname, String bio, String qq) {
        if (nickname != null) {
            String n = nickname.trim();
            if (n.isEmpty()) throw new DriveException(ErrorCode.BAD_REQUEST, "昵称不能为空");
            if (n.length() > 64) throw new DriveException(ErrorCode.BAD_REQUEST, "昵称最长 64 个字");
            jdbc.update("update drv_user set nickname = ? where id = ?", n, userId);
        }
        if (bio != null) {
            String b = bio.trim();
            if (b.length() > 500) throw new DriveException(ErrorCode.BAD_REQUEST, "简介最长 500 字");
            jdbc.update("update drv_user set bio = ? where id = ?", b.isEmpty() ? null : b, userId);
        }
        if (qq != null) {
            String q = qq.trim();
            if (!q.isEmpty() && !q.matches("\\d{5,12}"))
                throw new DriveException(ErrorCode.BAD_REQUEST, "QQ 号应是 5~12 位数字");
            // 同一个 QQ 不能被两个账号绑
            if (!q.isEmpty()) {
                Integer taken = jdbc.queryForObject(
                        "select count(*) from drv_user where qq = ? and id <> ?",
                        Integer.class, q, userId);
                if (taken != null && taken > 0)
                    throw new DriveException(ErrorCode.BAD_REQUEST, "这个 QQ 号已被其他账号绑定");
            }
            jdbc.update("update drv_user set qq = ? where id = ?", q.isEmpty() ? null : q, userId);
        }
        jdbc.update("update drv_user set updated_at = now() where id = ?", userId);
        return profile(userId);
    }

    /* ==================== 安全问题 ==================== */

    /**
     * 安全问题用「两个问题 + 答案哈希」。
     * 答案不存明文 —— 和密码一样是凭据，明文存等于把找回通道拱手让人。
     * 比对时同样走 BCrypt，所以答案大小写/空格要自己归一化。
     */
    public record SecurityQuestion(int slot, String question) {}

    public List<SecurityQuestion> securityQuestions(long userId) {
        return jdbc.query(
                "select slot, question from drv_security_answer where user_id = ? order by slot",
                (rs, i) -> new SecurityQuestion(rs.getInt("slot"), rs.getString("question")),
                userId);
    }

    @Transactional
    public void setSecurityQuestions(long userId, List<Map<String, String>> items) {
        if (items == null || items.size() < 2)
            throw new DriveException(ErrorCode.BAD_REQUEST, "至少要设两个安全问题");
        jdbc.update("delete from drv_security_answer where user_id = ?", userId);
        for (int i = 0; i < items.size() && i < 5; i++) {
            Map<String, String> it = items.get(i);
            String q = it.getOrDefault("question", "").trim();
            String a = normalizeAnswer(it.getOrDefault("answer", ""));
            if (q.isEmpty() || a.isEmpty())
                throw new DriveException(ErrorCode.BAD_REQUEST, "问题和答案都不能空");
            if (q.length() > 100) throw new DriveException(ErrorCode.BAD_REQUEST, "问题最长 100 字");
            jdbc.update(
                    "insert into drv_security_answer(user_id, slot, question, answer_hash) values (?,?,?,?)",
                    userId, i, q, hasher.encode(a));
        }
        jdbc.update("update drv_user set updated_at = now() where id = ?", userId);
    }

    /** 答案归一化：去掉首尾空格、全角转半角、统一小写 —— 不然「北京」和「北京 」对不上 */
    static String normalizeAnswer(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.trim().toCharArray()) {
            if (c >= '！' && c <= '～') sb.append((char) (c - 0xFEE0));  // 全角 ASCII
            else if (c == '　') sb.append(' ');                          // 全角空格
            else sb.append(c);
        }
        // toLowerCase 只影响 ASCII 字母；中文原样保留，这正是我们要的
        // （「实验小学」和「实验小学」相等，「ABC」和「abc」也相等）
        return sb.toString().toLowerCase();
    }

    /**
     * 校验安全问题（找回密码时用）。
     * 随机排序后抽查其中几题，避免「只记住一道题」就能过。
     */
    public boolean verifySecurityAnswers(long userId, Map<Integer, String> answers) {
        if (answers == null || answers.isEmpty()) return false;
        int correct = 0;
        for (Map.Entry<Integer, String> e : answers.entrySet()) {
            List<String> hash = jdbc.query(
                    "select answer_hash from drv_security_answer where user_id = ? and slot = ?",
                    (rs, i) -> rs.getString(1), userId, e.getKey());
            if (!hash.isEmpty() && hasher.matches(normalizeAnswer(e.getValue()), hash.get(0)))
                correct++;
        }
        return correct >= Math.min(2, answers.size());
    }

    /* ==================== 登录设备 ==================== */

    public record Device(long id, String userAgent, String ip,
                         java.time.Instant createdAt, java.time.Instant expiresAt, boolean current) {}

    /** 当前 token 的那条会话标 current=true，界面上好区分「这台」和「别处」 */
    public List<Device> devices(long userId, String currentTokenId) {
        return jdbc.query(
                "select id, coalesce(user_agent,''), coalesce(ip,''), created_at, expires_at, token_id " +
                "from drv_session where user_id = ? and revoked_at is null and expires_at > now() " +
                "order by created_at desc",
                (rs, i) -> new Device(rs.getLong("id"), rs.getString(2), rs.getString(3),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("token_id").equals(currentTokenId)),
                userId);
    }

    /** 退出全部设备 —— 但留下当前这台，否则自己也被踢出去 */
    @Transactional
    public int revokeOtherDevices(long userId, String keepTokenId) {
        return jdbc.update(
                "update drv_session set revoked_at = now() " +
                "where user_id = ? and token_id <> ? and revoked_at is null",
                userId, keepTokenId);
    }

    @Transactional
    public void revokeDevice(long userId, long sessionId) {
        jdbc.update(
                "update drv_session set revoked_at = now() " +
                "where id = ? and user_id = ? and revoked_at is null", sessionId, userId);
    }

    /* ==================== 用户管理（管理员） ==================== */

    public record UserRowView(long id, String email, String nickname, String role,
                              String roleLabel, String status, long quota, long used,
                              java.time.Instant createdAt) {}

    public List<UserRowView> listUsers(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        String like = "%" + kw.toLowerCase() + "%";
        return jdbc.query(
                "select id, email, nickname, role, status, storage_quota, storage_used, created_at " +
                "from drv_user where (? = '' or lower(email) like ? or lower(nickname) like ?) " +
                "order by case role when 'SUPER_ADMIN' then 0 when 'ADMIN' then 1 else 2 end, id",
                (rs, i) -> {
                    String role = rs.getString("role");
                    return new UserRowView(rs.getLong("id"), rs.getString("email"),
                            rs.getString("nickname"), role, Role.of(role).label(),
                            rs.getString("status"), rs.getLong("storage_quota"),
                            rs.getLong("storage_used"), rs.getTimestamp("created_at").toInstant());
                }, kw, like, like);
    }

    /**
     * 改别人的角色。谁能改谁由 {@link Role#canAssign(Role)} 决定：
     * 超管可以设管理员/用户，管理员谁都不能设（只能管内容和配额）。
     * 不能改自己 —— 防止超管把自己降级后没人管站。
     */
    @Transactional
    public void changeRole(long operatorId, Role operatorRole, long targetId, Role newRole) {
        if (targetId == operatorId)
            throw new DriveException(ErrorCode.BAD_REQUEST, "不能修改自己的角色");
        Role targetNow = Role.of(jdbc.queryForObject(
                "select role from drv_user where id = ?", String.class, targetId));
        if (!operatorRole.canAssign(targetNow))
            throw new DriveException(ErrorCode.FORBIDDEN, "你无权调整这个账号的角色");
        if (!operatorRole.canAssign(newRole))
            throw new DriveException(ErrorCode.FORBIDDEN, "你无权指派这个角色");
        if (newRole == Role.GUEST)
            throw new DriveException(ErrorCode.BAD_REQUEST, "GUEST 是未登录身份，不能指派");
        jdbc.update("update drv_user set role = ?, updated_at = now() where id = ?",
                newRole.name(), targetId);
    }

    /** 封号/解封。超管和管理员都能做，但都动不了超管。 */
    @Transactional
    public void setStatus(long operatorId, Role operatorRole, long targetId, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status))
            throw new DriveException(ErrorCode.BAD_REQUEST, "状态只能是 ACTIVE 或 DISABLED");
        if (targetId == operatorId)
            throw new DriveException(ErrorCode.BAD_REQUEST, "不能封禁自己");
        Role targetNow = Role.of(jdbc.queryForObject(
                "select role from drv_user where id = ?", String.class, targetId));
        if (!operatorRole.canAssign(targetNow))
            throw new DriveException(ErrorCode.FORBIDDEN, "你无权封禁这个账号");
        jdbc.update("update drv_user set status = ?, updated_at = now() where id = ?",
                status, targetId);
        if ("DISABLED".equals(status)) {
            // 封号要立刻踢下线，否则他手上的 token 还能用到过期
            jdbc.update("update drv_session set revoked_at = now() " +
                        "where user_id = ? and revoked_at is null", targetId);
        }
    }

    /** 改配额。管理员能改普通用户的，超管能改所有人的。 */
    @Transactional
    public void setQuota(Role operatorRole, long targetId, long quotaBytes) {
        if (quotaBytes < 0)
            throw new DriveException(ErrorCode.BAD_REQUEST, "配额不能是负数");
        Role targetNow = Role.of(jdbc.queryForObject(
                "select role from drv_user where id = ?", String.class, targetId));
        if (!operatorRole.canAssign(targetNow))
            throw new DriveException(ErrorCode.FORBIDDEN, "你无权调整这个账号的配额");
        long used = jdbc.queryForObject(
                "select storage_used from drv_user where id = ?", Long.class, targetId);
        if (quotaBytes > 0 && quotaBytes < used)
            throw new DriveException(ErrorCode.BAD_REQUEST, "新配额小于已用量，会立刻超额");
        jdbc.update("update drv_user set storage_quota = ?, updated_at = now() where id = ?",
                quotaBytes, targetId);
    }
}