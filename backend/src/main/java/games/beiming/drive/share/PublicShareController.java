package games.beiming.drive.share;

import games.beiming.drive.fs.FileService;
import games.beiming.drive.storage.BlobStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 匿名分享访问：不需要登录。
 * /s/{id}        查看分享内容（列表或单个文件信息）
 * /s/{id}/dl     下载（同样支持 Range）
 */
@RestController
@RequestMapping("/api/share")
public class PublicShareController {

    private final ShareService shares;
    private final FileService files;
    private final BlobStore blobs;

    public PublicShareController(ShareService shares, FileService files, BlobStore blobs) {
        this.shares = shares;
        this.files = files;
        this.blobs = blobs;
    }

    @GetMapping("/{id}")
    public Object view(@PathVariable String id,
                       @RequestParam(required = false) String password) {
        ShareService.Share s = shares.byId(id);
        if (s.hasPassword() && (password == null || password.isBlank())) {
            // 只告诉前端需要提取码，不泄露任何文件信息
            return Map.of("code", 0, "message", "need password",
                    "data", Map.of("requiresPassword", true, "shareId", id));
        }
        FileService.Node node = shares.resolve(id, password);
        if (node.isDir()) {
            List<FileService.Node> items = files.list(node.ownerId(), node.id());
            return Map.of("code", 0, "message", "success",
                    "data", Map.of("node", node, "items", items, "requiresPassword", s.hasPassword()));
        }
        return Map.of("code", 0, "message", "success",
                "data", Map.of("node", node, "requiresPassword", s.hasPassword()));
    }

    @GetMapping("/{id}/dl")
    public void download(@PathVariable String id,
                         @RequestParam(required = false) String password,
                         HttpServletRequest req,
                         HttpServletResponse res) throws IOException {
        FileService.Node node = shares.resolve(id, password);
        if (node.isDir() || node.sha256() == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
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

        if (!partial) shares.bumpDownload(id);

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