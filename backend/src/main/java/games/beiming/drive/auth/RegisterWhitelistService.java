package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 注册白名单。
 *
 * <h3>为什么要有这个东西</h3>
 * 花蝉 2026-11-28 定的：「只能有我们北冥管理官方有注册网盘的权限，其他人只有使用权」。
 * 也就是说网盘不是开放注册的 —— 注册权是内部权限，外部人只能用（浏览、下载、分享）。
 *
 * <h3>为什么做成表而不是写死几条规则</h3>
 * 「北冥管理官方」具体是谁我猜不出来：猜 QQ 号段会误伤，猜邮箱域名会漏人。
 * 做成表之后由超管自己在页面上维护，加一个人不用改代码重新部署。
 *
 * <h3>匹配规则</h3>
 * 注册表单只收邮箱。所以：
 * <ul>
 *   <li>{@code kind='qq'}：pattern 是 QQ 号，注册邮箱是 {@code <pattern>@qq.com} 就算命中</li>
 *   <li>{@code kind='email'}：pattern 是完整邮箱，完全相等才算</li>
 * </ul>
 * 两者都做小写归一 —— 邮箱大小写不敏感，`Huachan@QQ.com` 和 `huachan@qq.com` 是同一个人。
 */
@Service
public class RegisterWhitelistService {

    private final JdbcTemplate jdbc;

    public RegisterWhitelistService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(long id, String pattern, String kind, String note,
                        java.time.Instant createdAt) {}

    /**
     * 这个邮箱能不能注册。
     *
     * 注意：**白名单为空时放行**。理由是存量库和空库场景 ——
     * 全新部署时还没人往白名单里填东西，如果空表就全拒，那台机器就再也
     * 建不出第一个账号了（自举锁死，和「首个注册者升超管」是同一个坑的两种表现）。
     * 一旦有一条记录，就开始严格执行。
     *
     * @return true = 允许注册
     */
    public boolean allows(String email) {
        String normalized = normalize(email);
        if (normalized.isEmpty()) return false;

        Long total = jdbc.queryForObject(
                "select count(*) from drv_register_whitelist", Long.class);
        if (total == null || total == 0) {
            // 白名单还没配过 —— 放行，否则没人能注册，第一个超管都建不出来
            return true;
        }

        // 完整邮箱匹配
        Integer byEmail = jdbc.queryForObject("""
            select count(*) from drv_register_whitelist
             where kind = 'email' and lower(pattern) = ?
            """, Integer.class, normalized);
        if (byEmail != null && byEmail > 0) return true;

        // QQ 号匹配：邮箱必须正好是 <qq>@qq.com（也接受 @vip.qq.com / @foxmail.com）
        int at = normalized.indexOf('@');
        if (at > 0) {
            String local = normalized.substring(0, at);
            String domain = normalized.substring(at + 1);
            boolean qqDomain = domain.equals("qq.com")
                    || domain.equals("vip.qq.com")
                    || domain.equals("foxmail.com");
            if (qqDomain) {
                Integer byQq = jdbc.queryForObject("""
                    select count(*) from drv_register_whitelist
                     where kind = 'qq' and lower(pattern) = ?
                    """, Integer.class, local);
                if (byQq != null && byQq > 0) return true;
            }
        }
        return false;
    }

    /** 不允许就抛 403 —— 用 403 而不是 404，让人知道是「没资格」而不是「地址错了」 */
    public void requireAllowed(String email) {
        if (!allows(email)) {
            throw new DriveException(ErrorCode.FORBIDDEN,
                    "网盘暂不开放注册，请联系北冥管理员开通");
        }
    }

    public List<Entry> list() {
        return jdbc.query("""
            select id, pattern, kind, note, created_at
              from drv_register_whitelist
             order by created_at desc, id desc
            """, (rs, i) -> new Entry(
                rs.getLong("id"), rs.getString("pattern"), rs.getString("kind"),
                rs.getString("note"), rs.getTimestamp("created_at").toInstant()));
    }

    /**
     * 加一条。pattern 的有效性按 kind 校验 ——
     * qq 必须纯数字，email 必须带 @。不做校验的话，超管手滑填错一个字母，
     * 表现是「加进去了但那个人还是注册不了」，很难查。
     */
    public Entry add(String pattern, String kind, String note, long byUserId) {
        String p = normalize(pattern);
        String k = "qq".equalsIgnoreCase(kind) ? "qq" : "email";

        if (p.isEmpty()) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "匹配值不能为空");
        }
        if ("qq".equals(k) && !p.matches("[0-9]{5,12}")) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "QQ 号应为 5~12 位数字");
        }
        if ("email".equals(k) && !p.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "邮箱格式不对");
        }

        Integer dup = jdbc.queryForObject("""
            select count(*) from drv_register_whitelist
             where lower(pattern) = ? and kind = ?
            """, Integer.class, p, k);
        if (dup != null && dup > 0) {
            throw new DriveException(ErrorCode.CONFLICT, "这条白名单已经存在了");
        }

        jdbc.update("""
            INSERT INTO drv_register_whitelist(pattern, kind, note, created_by)
            VALUES (?,?,?,?)
            """, p, k, note, byUserId);

        return jdbc.queryForObject("""
            select id, pattern, kind, note, created_at
              from drv_register_whitelist
             where lower(pattern) = ? and kind = ?
            """, (rs, i) -> new Entry(
                rs.getLong("id"), rs.getString("pattern"), rs.getString("kind"),
                rs.getString("note"), rs.getTimestamp("created_at").toInstant()), p, k);
    }

    public void remove(long id) {
        jdbc.update("delete from drv_register_whitelist where id = ?", id);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}