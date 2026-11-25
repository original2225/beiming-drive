package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder(10);

    public AuthService(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    public record UserRow(long id, String email, String nickname, String passwordHash,
                          String role, long quota, long used, String status) {
    }

    public record LoginResult(String token, Instant expiresAt, UserView user) {
    }

    public record UserView(long id, String email, String nickname, String role,
                           long storageQuota, long storageUsed) {
    }

    private static final RowMapper<UserRow> USER_MAPPER = (rs, i) -> new UserRow(
            rs.getLong("id"), rs.getString("email"), rs.getString("nickname"),
            rs.getString("password_hash"), rs.getString("role"),
            rs.getLong("storage_quota"), rs.getLong("storage_used"),
            rs.getString("status"));

    /** 首个注册用户自动成为 ADMIN，方便本机自举。 */
    @Transactional
    public LoginResult register(String email, String nickname, String password) {
        Long existing = jdbc.queryForObject(
                "select count(*) from drv_user", Long.class);
        boolean first = existing != null && existing == 0;

        String hash = hasher.encode(password);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into drv_user(email, nickname, password_hash, role) values (?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, email);
            ps.setString(2, nickname);
            ps.setString(3, hash);
            ps.setString(4, first ? "ADMIN" : "USER");
            return ps;
        }, kh);

        long id = java.util.Objects.requireNonNull(kh.getKey()).longValue();
        // 每个用户一个根目录
        jdbc.update("insert into drv_node(owner_id, parent_id, name, path, kind) " +
                "values (?, null, '', '/', 'dir')", id);
        return login(email, password);
    }

    @Transactional
    public LoginResult login(String email, String password) {
        List<UserRow> rows = jdbc.query(
                "select * from drv_user where email = ?", USER_MAPPER, email);
        if (rows.isEmpty()) {
            throw new DriveException(ErrorCode.BAD_CREDENTIALS);
        }
        UserRow u = rows.get(0);
        if (!hasher.matches(password, u.passwordHash())) {
            throw new DriveException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!"ACTIVE".equals(u.status())) {
            throw new DriveException(ErrorCode.ACCOUNT_DISABLED);
        }

        JwtService.Issued issued = jwt.issue(u.id(), u.role());
        jdbc.update("insert into drv_session(user_id, token_id, expires_at) values (?,?,?)",
                u.id(), issued.tokenId(), java.sql.Timestamp.from(issued.expiresAt()));

        return new LoginResult(issued.token(), issued.expiresAt(),
                new UserView(u.id(), u.email(), u.nickname(), u.role(), u.quota(), u.used()));
    }

    public void logout(String tokenId) {
        jdbc.update("update drv_session set revoked_at = now() " +
                "where token_id = ? and revoked_at is null", tokenId);
    }

    /** 会话有效性：token 未过期且未被吊销。 */
    public boolean sessionAlive(String tokenId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from drv_session " +
                "where token_id = ? and revoked_at is null and expires_at > now()",
                Integer.class, tokenId);
        return n != null && n > 0;
    }

    public UserView me(long userId) {
        UserRow u = jdbc.queryForObject("select * from drv_user where id = ?", USER_MAPPER, userId);
        if (u == null) throw new DriveException(ErrorCode.UNAUTHORIZED);
        return new UserView(u.id(), u.email(), u.nickname(), u.role(), u.quota(), u.used());
    }
}