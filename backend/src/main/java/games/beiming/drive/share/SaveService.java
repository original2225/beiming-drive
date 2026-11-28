package games.beiming.drive.share;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.fs.FileService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分享转存：把别人分享的文件（或目录）保存到自己的网盘。
 *
 * <p><b>零拷贝</b>：文件内容早就在 blobstore 里（按 sha256 去重），转存只新建一条
 * {@code drv_node} 指向同一个 sha，不复制一个字节。
 *
 * <p><b>GC 天然安全，不需要额外的引用计数表</b>：{@code BlobGcService.referencedShas()}
 * 的引用集是 {@code select distinct sha256 from drv_node}（外加头像），
 * 所以每多一条节点行就自动多一个引用。转存方把节点删了，只要原分享者的节点还在，
 * 这个 sha 仍被引用，不会被当成孤儿清掉。只有当所有引用都消失时才会回收 —— 这是对的。
 *
 * <p><b>目标目录</b>：与「上传」一致，转存到用户指定的、自己有上传权的目录
 * （{@code targetParentId} 为空则落到根目录）。系统没有固定的「我的网盘」目录，
 * 用户本就是在共享树的各目录里工作，所以这里不硬造一个。
 *
 * <p><b>目录转存限管理员</b>：本项目「文件夹只有管理员能建」（{@code FileService.mkdir}
 * 里写死）。因此普通用户转存文件夹会在建目录时被拒（403），和手动新建文件夹一致，
 * 这是既有权限模型决定的，不是转存缺陷。
 */
@Service
public class SaveService {

    private final FileService files;
    private final ShareService shares;
    private final JdbcTemplate jdbc;

    public SaveService(FileService files, ShareService shares, JdbcTemplate jdbc) {
        this.files = files;
        this.shares = shares;
        this.jdbc = jdbc;
    }

    /** 转存结果：落到哪个节点、复制了多少文件/目录、合计字节。 */
    public record SaveResult(long nodeId, String name, boolean dir,
                             int files, int dirs, long bytes) {
    }

    @Transactional
    public SaveResult save(long userId, String shareId, String password, Long targetParentId) {
        // resolve 会校验：未过期、未超次、密码正确；返回分享指向的源节点。
        // 注意：转存**不算下载**，不 bump downloadCount。
        FileService.Node src = shares.resolve(shareId, password);

        FileService.Node target = (targetParentId == null)
                ? files.rootOf(userId) : files.byId(userId, targetParentId);
        if (!target.isDir()) throw new DriveException(ErrorCode.BAD_REQUEST, "目标不是目录");

        int[] counts = {0, 0};   // [files, dirs]
        long[] bytes = {0L};
        long newId = copyInto(userId, target.id(), src, counts, bytes);
        // dirs 只算**内部子目录**，不含用户看到的那一个顶层（顶层就是 SaveResult.dir 本身）
        int innerDirs = src.isDir() ? Math.max(0, counts[1] - 1) : 0;
        return new SaveResult(newId, src.name(), src.isDir(), counts[0], innerDirs, bytes[0]);
    }

    /**
     * 递归复制。关键一点：列源目录的子项必须用**源节点所有者**的身份
     * （{@code files.list(src.ownerId(), src.id())}），不能用当前转存人 ——
     * 否则会做 BROWSE 权限校验，转存人对分享者的私有目录没有权限，直接 404。
     * 这和 PublicShareController 里列分享目录用的是同一个手法。
     */
    private long copyInto(long userId, long parentId, FileService.Node src,
                          int[] counts, long[] bytes) {
        // 自动改名避让：共享树 path 全局唯一，转存到根目录时很容易撞到源文件自己的
        // `/名字`。**绝不能覆盖** —— putFile 遇同名会改写那条节点的 sha，那就把
        // 分享者的原文件顶掉了（实测过：转存后 owner 还是原作者、原文件内容变了）。
        String name = freeName(parentId, src.name(), src.isDir());
        if (src.isDir()) {
            FileService.Node dir = files.mkdir(userId, parentId, name);  // 非管理员会在此 403
            counts[1]++;
            List<FileService.Node> children = files.list(src.ownerId(), src.id());
            for (FileService.Node child : children) {
                copyInto(userId, dir.id(), child, counts, bytes);
            }
            return dir.id();
        }
        FileService.Node f = files.putFile(userId, parentId, name,
                src.mime(), src.sha256(), src.size());
        counts[0]++;
        bytes[0] += src.size();
        return f.id();
    }

    /** 在该父目录下找一个不撞 path 的名字：原名被占就加「 (1)」「 (2)」… */
    private String freeName(long parentId, String name, boolean isDir) {
        String parentPath = jdbc.queryForObject(
                "select path from drv_node where id=?", String.class, parentId);
        String base = name;
        String ext = "";
        if (!isDir) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        }
        String candidate = name;
        int n = 0;
        while (true) {
            String path = "/".equals(parentPath) ? "/" + candidate : parentPath + "/" + candidate;
            Integer c = jdbc.queryForObject(
                    "select count(*) from drv_node where path=? and status<>'TRASHED'",
                    Integer.class, path);
            if (c == null || c == 0) return candidate;
            n++;
            candidate = base + " (" + n + ")" + ext;
        }
    }
}
