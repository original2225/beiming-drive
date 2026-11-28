package games.beiming.drive.fs;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.storage.BlobStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件下载：必须支持 Range，否则多线程分段下载退化成单线程。
 * 返回 206 + Content-Range，前端/下载器才能并发切片。
 */
@RestController
@RequestMapping("/api/v1/files")
public class DownloadController {

    private final FileService files;
    private final BlobStore blobs;
    private final CurrentUser currentUser;
    private final UserFileMarksService marks;
    private final NodePermissionService perms;

    public DownloadController(FileService files, BlobStore blobs,
                              CurrentUser currentUser, UserFileMarksService marks,
                              NodePermissionService perms) {
        this.files = files;
        this.blobs = blobs;
        this.currentUser = currentUser;
        this.marks = marks;
        this.perms = perms;
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable long id,
                         HttpServletRequest req,
                         HttpServletResponse res) throws IOException {
        CurrentUser.Auth who = currentUser.require();
        FileService.Node node = files.byId(who.userId(), id);
        if (node.isDir() || node.sha256() == null) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "该条目不是可下载文件");
        }
        // 共享树里「能看」不等于「能给原件」—— 浏览权只能列目录，
        // 下载要单独有 can_download。花蝉要的「预览/访问/下载」分开就是这么落地的。
        perms.require(who.userId(), id, NodePermissionService.Right.DOWNLOAD,
                NodePermissionService.bypass(who.role()));

        long size = blobs.sizeOf(node.sha256());
        String range = req.getHeader("Range");

        long start = 0;
        long end = size - 1;
        boolean partial = false;

        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int comma = spec.indexOf(',');
            if (comma >= 0) spec = spec.substring(0, comma);   // 只服务单段
            String[] parts = spec.split("-", -1);
            try {
                if (parts.length == 2) {
                    if (!parts[0].isBlank()) {
                        start = Long.parseLong(parts[0].trim());
                        if (!parts[1].isBlank()) end = Long.parseLong(parts[1].trim());
                    } else if (!parts[1].isBlank()) {
                        // bytes=-N 表示最后 N 字节
                        long n = Long.parseLong(parts[1].trim());
                        start = Math.max(0, size - n);
                        end = size - 1;
                    }
                    start = Math.max(0, Math.min(start, size - 1));
                    end = Math.max(start, Math.min(end, size - 1));
                    partial = true;
                }
            } catch (NumberFormatException e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
        }

        long length = end - start + 1;

        // 只在"第一段"记一次下载记录。
        // 这个接口支持 Range，专属下载器会把一个文件切成几十段并发拉，
        // 每段都记的话"下载次数"会变成 30、90 这种鬼数字。
        // start == 0 是每轮下载的第一次请求，正好一次。
        if (!partial || start == 0) {
            marks.recordDownload(currentUser.require().userId(), id,
                    node.name(), node.path(), size);
        }

        res.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        res.setContentType(node.mime() == null ? "application/octet-stream" : node.mime());
        res.setHeader("Accept-Ranges", "bytes");
        res.setHeader("Content-Length", Long.toString(length));
        res.setHeader("ETag", "\"" + node.sha256() + "\"");
        res.setHeader("Cache-Control", "private, max-age=0");
        if (partial) {
            res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }

        String encoded = URLEncoder.encode(node.name(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        res.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + encoded);

        try (FileChannel ch = blobs.openChannel(node.sha256());
             OutputStream out = res.getOutputStream()) {
            ch.position(start);
            long left = length;
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (left > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), left));
                int read = ch.read(buf);
                if (read <= 0) break;
                out.write(buf.array(), 0, read);
                left -= read;
            }
            out.flush();
        }
    }

    /**
     * 在线预览：和下载同样的 Range 逻辑，唯一区别是 Content-Disposition 用 inline。
     * 浏览器据此决定「直接显示」而不是「弹出下载框」。视频拖动进度条依赖 Range 支持。
     */
    @GetMapping("/{id}/preview")
    public void preview(@PathVariable long id,
                        HttpServletRequest req,
                        HttpServletResponse res) throws IOException {
        CurrentUser.Auth who = currentUser.require();
        FileService.Node node = files.byId(who.userId(), id);
        if (node.isDir() || node.sha256() == null) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "该条目不是可预览文件");
        }
        // 预览和下载分开授权：can_preview 只给人看内容，不给原始文件。
        // 这是「能看不能下」唯一能落地的位置。
        perms.require(who.userId(), id, NodePermissionService.Right.PREVIEW,
                NodePermissionService.bypass(who.role()));
        String mime = node.mime() == null ? "application/octet-stream" : node.mime();
        // 只允许浏览器内联渲染的类型，其他一律退回下载，避免把用户上传的 html 当页面执行
        if (!isInlineSafe(mime, node.name())) {
            res.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
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
        res.setContentType(mime);
        res.setHeader("Accept-Ranges", "bytes");
        res.setContentLengthLong(length);
        if (partial) res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        res.setHeader("Content-Disposition", "inline");

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

    /** 白名单：只有这些类型允许内联渲染。html/svg 是 XSS 高危，一律不给 inline。 */
    private static boolean isInlineSafe(String mime, String name) {
        String m = mime.toLowerCase();
        if (m.startsWith("image/")) {
            // svg 可以内嵌脚本，不放进白名单
            return !m.contains("svg");
        }
        if (m.startsWith("video/")) return true;
        if (m.startsWith("audio/")) return true;
        if (m.equals("application/pdf")) return true;
        if (m.startsWith("text/plain")) return true;
        if (m.equals("text/markdown")) return true;
        // 有些上传没有 mime，按扩展名兜底
        String n = name.toLowerCase();
        return n.endsWith(".md") || n.endsWith(".txt");
    }
}