package games.beiming.drive.auth;

import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 注册白名单管理。只有超管/管理员能调。
 *
 * 挂在 /admin/users 下面而不是新开 /admin/whitelist：
 * 它和用户管理是同一件事的两半 —— 一个管「已经在的人」，一个管「谁可以进来」。
 * 前端也就在同一个页面上放两块，超管不用来回跳。
 */
@RestController
@RequestMapping("/api/v1/admin/register-whitelist")
public class RegisterWhitelistController {

    private final RegisterWhitelistService whitelist;
    private final CurrentUser currentUser;

    public RegisterWhitelistController(RegisterWhitelistService whitelist,
                                       CurrentUser currentUser) {
        this.whitelist = whitelist;
        this.currentUser = currentUser;
    }

    private CurrentUser.Auth requireAdmin() {
        CurrentUser.Auth a = currentUser.require();
        if (!a.canManageAdmins() && !a.isAdmin()) {
            throw new DriveException(ErrorCode.FORBIDDEN, "只有管理员能维护白名单");
        }
        return a;
    }

    public record AddReq(String pattern, String kind, String note) {}

    @GetMapping
    public ApiResponse<List<RegisterWhitelistService.Entry>> list() {
        requireAdmin();
        return ApiResponse.ok(whitelist.list());
    }

    @PostMapping
    public ApiResponse<RegisterWhitelistService.Entry> add(@RequestBody AddReq req) {
        CurrentUser.Auth who = requireAdmin();
        return ApiResponse.ok(
                whitelist.add(req.pattern(), req.kind(), req.note(), who.userId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable long id) {
        requireAdmin();
        whitelist.remove(id);
        return ApiResponse.ok(null);
    }

    /**
     * 自测：这个邮箱现在能不能注册。
     * 超管填完一条白名单想确认没填错时用，不然只能自己拿那个邮箱去试着注册一遍。
     */
    @GetMapping("/check")
    public ApiResponse<Map<String, Object>> check(@RequestParam String email) {
        requireAdmin();
        return ApiResponse.ok(Map.of("email", email, "allowed", whitelist.allows(email)));
    }
}