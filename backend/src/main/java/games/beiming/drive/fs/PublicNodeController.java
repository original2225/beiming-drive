package games.beiming.drive.fs;

import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.storage.BlobStore;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 游客（不登录）访问**公开**内容的接口。
 *
 * <p>这条路只认 {@code drv_node.is_public}，不走 ACL、不发 token。
 * 鉴权上它必须挂在 {@code WebConfig} 的 {@code excludePathPatterns("/api/public/**")} 里，
 * 否则会被拦截器 401 挡掉。
 *
 * <p><b>is_public 不继承</b>（V9 迁移定的口径）：首页列全站公开条目平铺；
 * 点进某个公开目录时才列出它下面的公开子项，且目录自己不公开直接 404。
 */
@RestController
@RequestMapping("/api/public")
public class PublicNodeController {

    private final FileService files;
    private final BlobStore blobs;

    public PublicNodeController(FileService files, BlobStore blobs) {
        this.files = files;
        this.blobs = blobs;
    }

    /** 列公开内容。parentId 省略 = 全站公开条目平铺。 */
    @GetMapping("/nodes")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String parentId) {
        Long pid = null;
        if (parentId != null && !parentId.isBlank()) {
            try {
                pid = Long.parseLong(parentId.trim());
            } catch (NumberFormatException e) {
                pid = null;
            }
        }
        List<FileService.Node> items = files.listPublic(pid);
        return ApiResponse.ok(Map.of("items", items));
    }

    /** 公开文件的元信息（供下载前确认名字/大小）。 */
    @GetMapping("/nodes/{id}")
    public ApiResponse<FileService.Node> view(@PathVariable long id) {
        return ApiResponse.ok(files.publicById(id));
    }

    /** 公开文件下载，支持 Range（拖动进度条/断点续传）。逻辑与分享下载一致。 */
    @GetMapping("/nodes/{id}/dl")
    public void download(@PathVariable long id,
                         jakarta.servlet.http.HttpServletRequest req,
                         jakarta.servlet.http.HttpServletResponse res) throws IOException {
        FileService.Node node = files.publicById(id);
        if (node.isDir() || node.sha256() == null) {
            res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        long size = blobs.sizeOf(node.sha256());
        long start = 0, end = size - 1;
        boolean partial = false;
        String range = req.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int comma = spec.indexOf(',');
            if (comma >= 0) spec = spec.substring(0, comma);
            String[] p = spec.split("-", -1);
            try {
                if (p.length == 2) {
                    if (!p[0].isBlank()) {
                        start = Long.parseLong(p[0].trim());
                        if (!p[1].isBlank()) end = Long.parseLong(p[1].trim());
                    } else if (!p[1].isBlank()) {
                        start = Math.max(0, size - Long.parseLong(p[1].trim()));
                        end = size - 1;
                    }
                    start = Math.max(0, Math.min(start, size - 1));
                    end = Math.max(start, Math.min(end, size - 1));
                    partial = true;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        long length = end - start + 1;
        res.setStatus(partial ? 206 : 200);
        res.setContentType(node.mime() == null ? "application/octet-stream" : node.mime());
        res.setHeader("Accept-Ranges", "bytes");
        res.setHeader("Content-Length", Long.toString(length));
        if (partial) res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);

        String enc = URLEncoder.encode(node.name(), StandardCharsets.UTF_8).replace("+", "%20");
        res.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + enc);

        try (FileChannel ch = blobs.openChannel(node.sha256());
             OutputStream out = res.getOutputStream()) {
            ch.position(start);
            long left = length;
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (left > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), left));
                int n = ch.read(buf);
                if (n <= 0) break;
                out.write(buf.array(), 0, n);
                left -= n;
            }
            out.flush();
        }
    }
}
