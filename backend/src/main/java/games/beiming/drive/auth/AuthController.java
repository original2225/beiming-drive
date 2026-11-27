package games.beiming.drive.auth;

import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AvatarService avatarService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, AvatarService avatarService, CurrentUser currentUser) {
        this.authService = authService;
        this.avatarService = avatarService;
        this.currentUser = currentUser;
    }

    public record RegisterReq(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 1, max = 64) String nickname,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginReq(@Email @NotBlank String email, @NotBlank String password) {
    }

    @PostMapping("/register")
    public ApiResponse<AuthService.LoginResult> register(@Valid @RequestBody RegisterReq req) {
        return ApiResponse.ok(authService.register(req.email(), req.nickname(), req.password()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(authService.login(req.email(), req.password()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout(currentUser.require().tokenId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthService.UserView> me() {
        return ApiResponse.ok(authService.me(currentUser.require().userId()));
    }

    /** 换头像。multipart 单文件，字段名 file。 */
    @PostMapping("/avatar")
    public ApiResponse<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        long uid = currentUser.require().userId();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
        String key = avatarService.put(uid, bytes);
        // 返回带版本号的 url，前端直接换上就能看到新图
        return ApiResponse.ok(Map.of("avatarKey", key, "avatarUrl", "/api/v1/auth/avatar?v=" + System.currentTimeMillis()));
    }

    /** 删除头像，回到首字母占位。 */
    @DeleteMapping("/avatar")
    public ApiResponse<Void> deleteAvatar() {
        avatarService.clear(currentUser.require().userId());
        return ApiResponse.ok(null);
    }

    /**
     * 读头像图片本体。
     *
     * <p>为什么不走 /files 那套鉴权：{@code <img src>} 带不了 Authorization 头，
     * 所以这里接受 {@code access_token} 查询参数（和预览接口同一套做法）。
     * 必须设置 Content-Disposition: inline 且限制类型，否则 svg 类文件会成 XSS 面。
     */
    @GetMapping("/avatar")
    public ResponseEntity<byte[]> avatar(@RequestParam(required = false) Long uid) {
        // 鉴权交给 AuthInterceptor：它已经支持从 access_token 查询参数取 token，
        // 所以 <img src="/api/v1/auth/avatar?v=..&access_token=.."> 能直接工作。
        CurrentUser.Auth me = currentUser.require();
        // 允许看别人的头像（群里显示成员头像要用），默认看自己
        long target = (uid == null) ? me.userId() : uid;

        byte[] body = avatarService.load(target);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }
        String mime = avatarService.mimeOf(target);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mime)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                // 头像是按版本换 URL 的，可以放心长缓存
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=604800")
                .body(body);
    }
}
