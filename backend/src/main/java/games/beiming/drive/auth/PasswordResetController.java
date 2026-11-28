package games.beiming.drive.auth;

import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 忘记密码。三个接口，对应三步：
 *
 *   1. POST /auth/forgot/questions  —— 给邮箱，取要回答的问题
 *   2. POST /auth/forgot/verify     —— 交答案，换一张重置票
 *   3. POST /auth/forgot/reset      —— 拿票 + 新密码
 *
 * 全部不需要登录（忘记密码的人当然登不进来），所以三条路径必须
 * 加进 WebConfig 的 excludePathPatterns，否则会被拦截器 401 挡掉。
 */
@RestController
@RequestMapping("/api/v1/auth/forgot")
public class PasswordResetController {

    private final PasswordResetService resets;

    public PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    public record EmailReq(@NotBlank String email) {}

    @PostMapping("/questions")
    public ApiResponse<PasswordResetService.Challenge> questions(@Valid @RequestBody EmailReq req) {
        return ApiResponse.ok(resets.challenge(req.email()));
    }

    public record VerifyReq(@NotBlank String email, Map<Integer, String> answers) {}

    /**
     * 成功时只回一张票，不回「你答对了几题」。
     * 失败时统一是「答案不正确」—— 说清哪题错等于把题变成可以逐个试的。
     */
    @PostMapping("/verify")
    public ApiResponse<Map<String, String>> verify(@Valid @RequestBody VerifyReq req) {
        if (req.answers() == null || req.answers().isEmpty()) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "请回答安全问题");
        }
        String ticket = resets.verify(req.email(), req.answers());
        return ApiResponse.ok(Map.of("ticket", ticket));
    }

    public record ResetReq(@NotBlank String ticket, @NotBlank String password) {}

    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset(@Valid @RequestBody ResetReq req) {
        resets.reset(req.ticket(), req.password());
        // 不自动登录：密码刚改完，让用户自己用新密码登一次，
        // 顺便验证他记住了。自动登录反而掩盖「新密码对不对」这件事。
        return ApiResponse.ok(Map.of("ok", true));
    }
}