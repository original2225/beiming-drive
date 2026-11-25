package games.beiming.drive.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 前端静态托管：把 frontend/dist 挂在根路径，前端与后端同端口交付。
 *
 * 踩过的坑（都已避开）：
 * 1. 返回 ResponseEntity<Resource> 会走 Spring 的 message converter，
 *    对 UrlResource 触发序列化异常 -> 静态资源全线 500。这里直接写 OutputStream。
 * 2. 不做 SPA fallback，刷新 /shares 会 404 —— 前端路由路径统一回落 index.html。
 * 3. 本控制器不能碰 /api/**，否则和 REST 接口抢路由。
 */
@Controller
public class SpaWebController {

    private final Path dist;

    public SpaWebController(@Value("${drive.web.dist:}") String distDir) {
        this.dist = (distDir == null || distDir.isBlank())
                ? null : Paths.get(distDir).toAbsolutePath().normalize();
    }

    private void send(HttpServletResponse res, Path file) throws IOException {
        if (dist == null || file == null || !Files.exists(file) || Files.isDirectory(file)) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String name = file.getFileName().toString();
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType(contentType(name));
        res.setContentLengthLong(Files.size(file));
        res.setHeader("Cache-Control", name.endsWith(".html")
                ? "no-cache" : "public, max-age=31536000, immutable");
        try (OutputStream out = res.getOutputStream()) {
            Files.copy(file, out);
        }
    }

    private static String contentType(String n) {
        String s = n.toLowerCase();
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".html")) return "text/html; charset=utf-8";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".svg")) return "image/svg+xml";
        if (s.endsWith(".ico")) return "image/x-icon";
        if (s.endsWith(".json") || s.endsWith(".map")) return "application/json";
        if (s.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    @GetMapping("/assets/*")
    public void assets(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String rel = req.getRequestURI().substring("/assets/".length());
        if (rel.contains("..")) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        send(res, dist == null ? null : dist.resolve("assets").resolve(rel).normalize());
    }

    @GetMapping({"/", "/index.html", "/login", "/shares", "/downloader", "/s/*"})
    public void index(HttpServletResponse res) throws IOException {
        send(res, dist == null ? null : dist.resolve("index.html"));
    }

    /**
     * 根级静态文件：favicon.png / whale-flat-128.png 等。
     *
     * 坑：Spring PathPattern 的正则约束里**不能出现捕获组**，
     * 写 \\(png|ico|...\\) 会直接抛 IllegalArgumentException，
     * 而且是在路由匹配阶段抛，表现为所有静态资源 500。后缀在方法里自己判。
     */
    @GetMapping("/{name:[a-zA-Z0-9._-]+}")
    public void rootFile(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String name = req.getRequestURI().substring(1);
        if (name.contains("..") || !name.contains(".")) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        send(res, dist == null ? null : dist.resolve(name).normalize());
    }
}