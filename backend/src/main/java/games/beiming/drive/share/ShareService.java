package games.beiming.drive.share;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.fs.FileService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 分享：短链 token 指向一个文件或目录。可选密码、可选过期、可限次。
 */
@Service
public class ShareService {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RND = new SecureRandom();
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder(8);

    private final JdbcTemplate jdbc;
    private final FileService files;

    public ShareService(JdbcTemplate jdbc, FileService files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    public record Share(String id, long ownerId, long nodeId, boolean hasPassword,
                        Instant expiresAt, int downloadCount, Integer maxDownloads,
                        Instant createdAt) {
    }

    private static final RowMapper<Share> MAPPER = (rs, i) -> new Share(
            rs.getString("id"), rs.getLong("owner_id"), rs.getLong("node_id"),
            rs.getString("password_hash") != null,
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
            rs.getInt("download_count"),
            (Integer) rs.getObject("max_downloads"),
            rs.getTimestamp("created_at").toInstant());

    private String newId() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Transactional
    public Share create(long ownerId, long nodeId, String password, Integer expireHours,
                        Integer maxDownloads) {
        FileService.Node node = files.byId(ownerId, nodeId);   // 顺带校验归属
        String id = newId();
        Timestamp exp = expireHours == null ? null
                : Timestamp.from(Instant.now().plusSeconds(expireHours * 3600L));
        String pwHash = (password == null || password.isBlank()) ? null : hasher.encode(password);
        jdbc.update("insert into drv_share(id, owner_id, node_id, password_hash, expires_at, " +
                        "max_downloads) values (?,?,?,?,?,?)",
                id, ownerId, node.id(), pwHash, exp, maxDownloads);
        return byId(id);
    }

    public Share byId(String id) {
        List<Share> rows = jdbc.query("select * from drv_share where id=?", MAPPER, id);
        if (rows.isEmpty()) throw new DriveException(ErrorCode.NOT_FOUND, "分享不存在");
        return rows.get(0);
    }

    public List<Share> listMine(long ownerId) {
        return jdbc.query("select * from drv_share where owner_id=? order by created_at desc",
                MAPPER, ownerId);
    }

    @Transactional
    public void revoke(long ownerId, String id) {
        int n = jdbc.update("delete from drv_share where id=? and owner_id=?", id, ownerId);
        if (n == 0) throw new DriveException(ErrorCode.NOT_FOUND, "分享不存在");
    }

    /** 访问前校验：未过期、未超次、密码正确。返回对应节点。 */
    public FileService.Node resolve(String shareId, String password) {
        Share s = byId(shareId);
        if (s.expiresAt() != null && s.expiresAt().isBefore(Instant.now())) {
            throw new DriveException(ErrorCode.STATE_CONFLICT, "分享已过期");
        }
        if (s.maxDownloads() != null && s.downloadCount() >= s.maxDownloads()) {
            throw new DriveException(ErrorCode.STATE_CONFLICT, "分享下载次数已用完");
        }
        if (s.hasPassword()) {
            String hash = jdbc.queryForObject(
                    "select password_hash from drv_share where id=?", String.class, shareId);
            if (password == null || !hasher.matches(password, hash)) {
                throw new DriveException(ErrorCode.FORBIDDEN, "提取码不正确");
            }
        }
        return files.byId(s.ownerId(), s.nodeId());
    }

    public void bumpDownload(String shareId) {
        jdbc.update("update drv_share set download_count = download_count + 1 where id=?", shareId);
    }

    public Share shareOfNode(long ownerId, long nodeId) {
        List<Share> rows = jdbc.query(
                "select * from drv_share where owner_id=? and node_id=? order by created_at desc limit 1",
                MAPPER, ownerId, nodeId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}