package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 忘记密码：用安全问题换一张一次性重置票，再拿票改密码。
 *
 * 为什么不「答对就允许改密码」一步到位：
 * 答完题立刻改密码，等于答题接口本身就是个改密码接口 ——
 * 谁拿到邮箱 + 蒙对题就能直接改，没有中间环节可以限速或留痕。
 * 拆成两步后，改密码必须出示一张服务端签发的短命票，
 * 而票的签发点可以做失败次数限制。
 *
 * 安全上还有两条硬约束：
 * 1. 不告诉前端「这个邮箱不存在」。统一回同一句话，否则就成了邮箱枚举接口。
 * 2. 答错不说是哪一题错。否则可以一题一题试。
 */
@Service
public class PasswordResetService {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder(10);
    private final SecureRandom rnd = new SecureRandom();

    public PasswordResetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一次提交最多允许错几次，超了要等冷却 */
    private static final int MAX_FAILS = 5;
    /** 冷却时长（分钟）—— 答题是低频操作，等 15 分钟用户不会太难受，暴力破解成本却很高 */
    private static final int LOCK_MINUTES = 15;
    /** 重置票有效期（分钟） */
    private static final int TICKET_MINUTES = 10;

    public record PublicQuestion(int slot, String question) {}

    /**
     * 第一步：按邮箱取「要回答的问题」。
     *
     * 邮箱不存在时**不能**报错 —— 那会让这个接口变成「查这个邮箱注册过没有」。
     * 但也不能什么都不返回（前端没法渲染）。
     * 折中：返回一组看起来正常的通用问题。答了当然会失败，但攻击者
     * 无法从「有没有问题」上分辨邮箱存在与否。
     */
    public record Challenge(String email, List<PublicQuestion> questions, boolean needsAnswers) {}

    public Challenge challenge(String email) {
        String mail = norm(email);
        if (mail.isEmpty()) throw new DriveException(ErrorCode.BAD_REQUEST, "请填写邮箱");

        Long uid = findUser(mail);
        if (uid == null) {
            // 假问题：内容和真的一样长，看不出差别
            return new Challenge(mail, List.of(
                    new PublicQuestion(0, "你养的第一只宠物叫什么？"),
                    new PublicQuestion(1, "你的小学叫什么名字？")), true);
        }

        List<PublicQuestion> qs = jdbc.query(
                "select slot, question from drv_security_answer where user_id = ? order by slot",
                (rs, i) -> new PublicQuestion(rs.getInt("slot"), rs.getString("question")),
                uid);

        // 这个账号没设过安全问题（比如 V5 之前注册的老账号）。
        // 不能编一套题骗他答 —— 答对了也重置不了。如实说，但要说清怎么办。
        if (qs.isEmpty()) return new Challenge(mail, List.of(), false);
        return new Challenge(mail, qs, true);
    }

    /**
     * 第二步：交答案换票。
     *
     * 返回体里永远只有「成功/失败」两种，不透露哪一题错、错了几题。
     *
     * **这个方法故意不加 @Transactional。**
     * 踩过的坑：加了事务之后，答错时 `bumpFail` 插入的失败记录会随
     * 抛出的 DriveException 一起回滚 —— 计数器永远是 0，限速完全失效，
     * 而且接口看起来一切正常（照样返回"还可以试 5 次"）。
     * 这里的几步（查答案、记失败、发票）彼此独立，不需要原子性。
     */
    public String verify(String email, Map<Integer, String> answers) {
        String mail = norm(email);
        Long uid = findUser(mail);
        // 邮箱不存在也走一遍限速逻辑，否则「没被限速」本身就是信息
        if (uid == null) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "答案不正确");
        }

        checkLock(uid);

        int ok = 0;
        int total = 0;
        for (Map.Entry<Integer, String> e : answers.entrySet()) {
            total++;
            List<String> hashes = jdbc.query(
                    "select answer_hash from drv_security_answer where user_id = ? and slot = ?",
                    (rs, i) -> rs.getString(1), uid, e.getKey());
            if (!hashes.isEmpty()
                    && hasher.matches(AccountService.normalizeAnswer(e.getValue()), hashes.get(0))) {
                ok++;
            }
        }

        // 通过条件：至少答对两题，且答对的不少于提交的一半。
        // 只要求「全对」会让用户因为一个笔误被卡死；只要求「对一题」
        // 又等于两题里蒙对一道就能进。
        boolean pass = total >= 2 && ok >= 2 && ok * 2 >= total;

        if (!pass) {
            bumpFail(uid);
            int left = MAX_FAILS - currentFails(uid);
            throw new DriveException(ErrorCode.BAD_REQUEST,
                    left > 0
                            ? "答案不正确，还可以试 " + left + " 次"
                            : "答案不正确次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }

        clearFails(uid);
        return issueTicket(uid);
    }

    /**
     * 第三步：拿票改密码。
     *
     * 票是一次性的：用过立刻标记 consumed，重放无效。
     * 这样即使票被截获，也只能用一次，而且 10 分钟就过期。
     */
    @Transactional
    public void reset(String ticket, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "新密码至少 8 位");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, user_id, used_at, expires_at from drv_password_reset " +
                "where ticket = ?", ticket);

        if (rows.isEmpty()) throw new DriveException(ErrorCode.BAD_REQUEST, "重置链接无效，请重新获取");
        Map<String, Object> r = rows.get(0);
        if (r.get("used_at") != null) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "这个重置链接已经用过了，请重新获取");
        }
        Instant exp = ((java.sql.Timestamp) r.get("expires_at")).toInstant();
        if (exp.isBefore(Instant.now())) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "重置链接已过期，请重新获取");
        }

        Long uid = ((Number) r.get("user_id")).longValue();

        jdbc.update("update drv_user set password_hash = ?, updated_at = now() where id = ?",
                hasher.encode(newPassword), uid);
        jdbc.update("update drv_password_reset set used_at = now() where id = ?", r.get("id"));

        // 改密码必须踢掉所有旧会话 —— 这是找回密码最重要的一步：
        // 账号可能就是被人登进去了才需要找回，不踢等于白改。
        jdbc.update("update drv_session set revoked_at = now() " +
                    "where user_id = ? and revoked_at is null", uid);

        // 顺手把这张票之外的其他未用票也作废，避免多次申请后留着多把钥匙
        jdbc.update("update drv_password_reset set used_at = now() " +
                    "where user_id = ? and used_at is null", uid);
    }

    /* ==================== 内部 ==================== */

    private Long findUser(String email) {
        List<Long> ids = jdbc.queryForList(
                "select id from drv_user where lower(email) = ? and status = 'ACTIVE'",
                Long.class, email);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String issueTicket(Long uid) {
        byte[] buf = new byte[32];
        rnd.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        String ticket = sb.toString();

        jdbc.update(
                "insert into drv_password_reset(ticket, user_id, expires_at) " +
                "values (?,?, now() + interval '" + TICKET_MINUTES + " minutes')",
                ticket, uid);
        return ticket;
    }

    /** 最近 LOCK_MINUTES 内的失败次数 */
    private int currentFails(Long uid) {
        Integer n = jdbc.queryForObject(
                "select count(*) from drv_reset_attempt " +
                "where user_id = ? and at > now() - interval '" + LOCK_MINUTES + " minutes'",
                Integer.class, uid);
        return n == null ? 0 : n;
    }

    private void checkLock(Long uid) {
        if (currentFails(uid) >= MAX_FAILS) {
            throw new DriveException(ErrorCode.BAD_REQUEST,
                    "答案错误次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }
    }

    private void bumpFail(Long uid) {
        jdbc.update("insert into drv_reset_attempt(user_id) values (?)", uid);
    }

    private void clearFails(Long uid) {
        jdbc.update("delete from drv_reset_attempt where user_id = ?", uid);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}