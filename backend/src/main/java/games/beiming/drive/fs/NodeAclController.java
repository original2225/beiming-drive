package games.beiming.drive.fs;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 节点权限管理。只有管理员能调。
 *
 * 为什么单独一个控制器而不是塞进 FileController：
 * 「谁能看/谁能下载」是治理动作，和被治理的文件操作是两件事。
 * 混进去之后 FileController 会同时承担「文件操作」和「权限发放」两种职责，
 * 而这个项目里权限判断本来就已经很密，再混会更容易漏判。
 */
@RestController
@RequestMapping("/api/v1/admin/nodes")
public class NodeAclController {

    private final NodePermissionService perms;
    private final FileService files;
    private final CurrentUser currentUser;

    public NodeAclController(NodePermissionService perms, FileService files, CurrentUser currentUser) {
        this.perms = perms;
        this.files = files;
        this.currentUser = currentUser;
    }

    private CurrentUser.Auth requireAdmin() {
        CurrentUser.Auth a = currentUser.require();
        if (!NodePermissionService.bypass(a.role())) {
            throw new DriveException(ErrorCode.FORBIDDEN, "只有管理员能改权限");
        }
        return a;
    }

    /** 看某个节点上显式写了哪些授权（不含继承来的） */
    @GetMapping("/{id}/acl")
    public ApiResponse<List<NodePermissionService.AclRow>> acl(@PathVariable long id) {
        requireAdmin();
        return ApiResponse.ok(perms.aclOf(id));
    }

    public record PermDto(Boolean browse, Boolean preview, Boolean download, Boolean upload) {
        NodePermissionService.Perm toPerm() {
            return new NodePermissionService.Perm(
                    Boolean.TRUE.equals(browse), Boolean.TRUE.equals(preview),
                    Boolean.TRUE.equals(download), Boolean.TRUE.equals(upload));
        }
    }

    /**
     * 设置授权。三个入口合一：
     *   everyone  —— 对所有人的默认规则
     *   userId    —— 对某个人的规则（就近覆盖 everyone）
     *   两个都不给 —— 400
     *
     * 用 PUT 而不是 POST：同一个 (节点, 人) 永远只有一条规则，
     * 反复设置应该是「改成新的」，不是「再插一条」。
     */
    public record SetReq(PermDto everyone, Long userId, PermDto perm) {}

    @PutMapping("/{id}/acl")
    public ApiResponse<Map<String, Object>> set(@PathVariable long id, @RequestBody SetReq req) {
        CurrentUser.Auth who = requireAdmin();

        if (req.everyone() != null) {
            perms.grantEveryone(id, req.everyone().toPerm(), who.userId());
            return ApiResponse.ok(Map.of("ok", true, "scope", "everyone"));
        }
        if (req.userId() != null && req.perm() != null) {
            perms.grant(id, req.userId(), req.perm().toPerm(), who.userId());
            return ApiResponse.ok(Map.of("ok", true, "scope", "user"));
        }
        throw new DriveException(ErrorCode.BAD_REQUEST, "要么指定 everyone，要么给 userId + perm");
    }

    /** 收回某个人的授权（他退回继承上级规则） */
    @DeleteMapping("/{id}/acl/{userId}")
    public ApiResponse<Void> revoke(@PathVariable long id, @PathVariable long userId) {
        requireAdmin();
        perms.revoke(id, userId);
        return ApiResponse.ok(null);
    }

    /** 问「我对这个节点有什么权限」—— 前端据此决定按钮显不显示 */
    @GetMapping("/{id}/my-perm")
    public ApiResponse<Map<String, Boolean>> myPerm(@PathVariable long id) {
        CurrentUser.Auth who = currentUser.require();
        var p = perms.effective(who.userId(), id, NodePermissionService.bypass(who.role()));
        return ApiResponse.ok(Map.of(
                "browse", p.browse(), "preview", p.preview(),
                "download", p.download(), "upload", p.upload()));
    }
}