package games.beiming.drive.auth;

import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 设置页 + 用户管理页的后端接口。
 *
 * 权限分三层：
 * <ul>
 *   <li>自己的资料/安全问题/设备 —— 登录即可</li>
 *   <li>看用户列表、封号、改配额 —— ADMIN 起（isAdmin）</li>
 *   <li>指派管理员 —— 仅 SUPER_ADMIN（canManageAdmins）</li>
 * </ul>
 * 判断全部走 {@link Role}，这里只做「够不够格」的闸门，
 * 「能不能针对这个具体的人」由 AccountService 再判一次。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountService accounts;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public AccountController(AccountService accounts, AuthService authService, CurrentUser currentUser) {
        this.accounts = accounts;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    private CurrentUser.Auth me() { return currentUser.require(); }

    private void requireAdmin() {
        if (!me().isAdmin()) throw new DriveException(ErrorCode.FORBIDDEN);
    }

    private void requireSuperAdmin() {
        if (!me().canManageAdmins()) throw new DriveException(ErrorCode.FORBIDDEN);
    }

    /* ==================== 自己的资料 ==================== */

    @GetMapping("/profile")
    public ApiResponse<AccountService.Profile> profile() {
        return ApiResponse.ok(accounts.profile(me().userId()));
    }

    /** 三个字段都可省略（null = 这一项不改），所以不带 @NotBlank */
    public record ProfileReq(@Size(max = 64) String nickname,
                             @Size(max = 500) String bio,
                             @Size(max = 20) String qq) {}

    @PutMapping("/profile")
    public ApiResponse<AccountService.Profile> updateProfile(@Valid @RequestBody ProfileReq req) {
        return ApiResponse.ok(accounts.updateProfile(
                me().userId(), req.nickname(), req.bio(), req.qq()));
    }

    /* ==================== 安全问题 ==================== */

    @GetMapping("/security-questions")
    public ApiResponse<List<AccountService.SecurityQuestion>> securityQuestions() {
        return ApiResponse.ok(accounts.securityQuestions(me().userId()));
    }

    @PutMapping("/security-questions")
    public ApiResponse<Void> setSecurityQuestions(@RequestBody List<Map<String, String>> items) {
        accounts.setSecurityQuestions(me().userId(), items);
        return ApiResponse.ok(null);
    }

    /* ==================== 登录设备 ==================== */

    @GetMapping("/devices")
    public ApiResponse<List<AccountService.Device>> devices() {
        CurrentUser.Auth a = me();
        return ApiResponse.ok(accounts.devices(a.userId(), a.tokenId()));
    }

    /** 退出其他所有设备，保留当前这台 */
    @PostMapping("/devices/revoke-others")
    public ApiResponse<Map<String, Integer>> revokeOthers() {
        CurrentUser.Auth a = me();
        int n = accounts.revokeOtherDevices(a.userId(), a.tokenId());
        return ApiResponse.ok(Map.of("revoked", n));
    }

    @DeleteMapping("/devices/{id}")
    public ApiResponse<Void> revokeDevice(@PathVariable long id) {
        accounts.revokeDevice(me().userId(), id);
        return ApiResponse.ok(null);
    }

    /* ==================== 用户管理（管理员） ==================== */

    @GetMapping("/users")
    public ApiResponse<List<AccountService.UserRowView>> users(
            @RequestParam(required = false) String keyword) {
        requireAdmin();
        return ApiResponse.ok(accounts.listUsers(keyword));
    }

    public record RoleReq(@NotBlank String role) {}

    /** 指派管理员 —— 只有超管能调；具体谁能升谁由 Role.canAssign 判 */
    @PutMapping("/users/{id}/role")
    public ApiResponse<Void> changeRole(@PathVariable long id, @Valid @RequestBody RoleReq req) {
        requireSuperAdmin();
        CurrentUser.Auth a = me();
        accounts.changeRole(a.userId(), a.role(), id, Role.of(req.role()));
        return ApiResponse.ok(null);
    }

    public record StatusReq(@NotBlank String status) {}

    @PutMapping("/users/{id}/status")
    public ApiResponse<Void> setStatus(@PathVariable long id, @Valid @RequestBody StatusReq req) {
        requireAdmin();
        CurrentUser.Auth a = me();
        accounts.setStatus(a.userId(), a.role(), id, req.status());
        return ApiResponse.ok(null);
    }

    public record QuotaReq(long quota) {}

    @PutMapping("/users/{id}/quota")
    public ApiResponse<Void> setQuota(@PathVariable long id, @Valid @RequestBody QuotaReq req) {
        requireAdmin();
        CurrentUser.Auth a = me();
        accounts.setQuota(a.role(), id, req.quota());
        return ApiResponse.ok(null);
    }
}