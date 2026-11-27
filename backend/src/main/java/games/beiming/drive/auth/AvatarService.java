package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.storage.BlobStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 用户头像的存取。
 *
 * <p>存法：图片实体交给 {@link BlobStore}（和文件正文同一套存储，备份策略一致），
 * 数据库存两样：{@code avatar_sha}（内容 sha256，读图用）和
 * {@code avatar_key}（形如 {@code avatar/3-1699999999.png}，前端拼 URL 用）。
 *
 * <p>key 里带时间戳是为了换头像后 URL 变化 —— 否则浏览器拿缓存继续显示旧图。
 *
 * <p>为什么校验文件头而不是只信 Content-Type：客户端能随便报类型，
 * 若放 svg/html 进来，站内就多一个可执行脚本的 XSS 面。
 * 这里只放 png/jpeg/webp/gif，且用魔术字节判定，不看声明。
 */
@Service
public class AvatarService {

    /** 1 MB 上限。头像没必要更大，也顺手堵住拿它当图床传大文件。 */
    private static final int MAX_BYTES = 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;

    public AvatarService(JdbcTemplate jdbc, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    private enum Type {
        PNG("png"), JPEG("jpg"), WEBP("webp"), GIF("gif");

        final String ext;

        Type(String ext) {
            this.ext = ext;
        }
    }

    /** 按魔术字节判定格式，不认识返回 null */
    private static Type sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Type.PNG;
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Type.JPEG;
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Type.WEBP;
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return Type.GIF;
        }
        return null;
    }

    /** 上传/替换头像，返回新的 key（前端据此刷新 URL）。 */
    @Transactional
    public String put(long userId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "图片内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "头像不能超过 1 MB");
        }
        Type type = sniff(bytes);
        if (type == null) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "只支持 PNG / JPEG / WebP / GIF 图片");
        }

        BlobStore.Stored stored;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            stored = blobs.put(in);
        } catch (IOException e) {
            throw new IllegalStateException("头像写入失败", e);
        }

        String key = "avatar/" + userId + "-" + System.currentTimeMillis() + "." + type.ext;
        jdbc.update("update drv_user set avatar_sha=?, avatar_key=?, updated_at=now() where id=?",
                stored.sha256(), key, userId);

        // 旧图不删：blob 按 sha256 去重，可能被别的文件引用；头像文件小，留给将来 GC 统一处理
        return key;
    }

    public void clear(long userId) {
        jdbc.update("update drv_user set avatar_sha=null, avatar_key=null, updated_at=now() where id=?",
                userId);
    }

    /** 前端拼 URL 用的 key；没有头像返回 null */
    public String avatarKeyOf(long userId) {
        var rows = jdbc.queryForList("select avatar_key from drv_user where id=?", userId);
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("avatar_key");
    }

    /** 读头像字节；没有头像返回 null */
    public byte[] load(long userId) {
        var rows = jdbc.queryForList("select avatar_sha from drv_user where id=?", userId);
        if (rows.isEmpty()) return null;
        String sha = (String) rows.get(0).get("avatar_sha");
        if (sha == null || sha.isBlank() || !blobs.exists(sha)) return null;
        try (InputStream in = blobs.open(sha)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** 头像的 mime，用于响应头 */
    public String mimeOf(long userId) {
        String key = avatarKeyOf(userId);
        if (key == null) return "application/octet-stream";
        if (key.endsWith(".png")) return "image/png";
        if (key.endsWith(".webp")) return "image/webp";
        if (key.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }
}
