package games.beiming.drive.admin;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.fs.TrashCleanupJob;
import games.beiming.drive.storage.BlobGcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维接口：手动触发后台清理。
 *
 * <p>为什么要有：清理任务挂在每天凌晨 3 点，但「我现在就想清一下」是真实需求 ——
 * 磁盘快满了、或者刚改完保留天数想立刻验证。自动任务不能为这个理由改成每小时跑。
 *
 * <p>权限：仅 ADMIN。这些接口动的是全站数据，不是「当前用户自己的文件」，
 * 所以不能放在 /files 下面靠普通登录态就够了。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMaintenanceController {

    private final TrashCleanupJob cleanup;
    private final BlobGcService gc;
    private final CurrentUser currentUser;

    public AdminMaintenanceController(TrashCleanupJob cleanup, BlobGcService gc,
                                      CurrentUser currentUser) {
        this.cleanup = cleanup;
        this.gc = gc;
        this.currentUser = currentUser;
    }

    public record CleanupResult(int expiredPurged, BlobGcService.GcReport gc) {
    }

    /**
     * 立刻跑一轮：清过期回收站 + 全盘孤儿对账。
     *
     * <p>幂等 —— 没有到期的就什么都不做，连跑十次也不会误删（有冷静期 + 引用检查兜着）。
     */
    @PostMapping("/trash/cleanup")
    public ApiResponse<CleanupResult> cleanupNow() {
        requireAdmin();
        int purged = cleanup.expireTrash();
        BlobGcService.GcReport report = gc.collect(false);
        return ApiResponse.ok(new CleanupResult(purged, report));
    }

    /**
     * 只对账不动手。先看「会删哪些、能释放多少」再决定要不要真跑。
     *
     * @param dryRun 默认 true。这里是唯一一个 dryRun 默认开的管理接口，
     *               因为它的默认用途就是「让我先看一眼」，真要删有上面那个接口。
     */
    @GetMapping("/storage/gc")
    public ApiResponse<BlobGcService.GcReport> gcPreview(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        requireAdmin();
        return ApiResponse.ok(gc.collect(dryRun));
    }

    private void requireAdmin() {
        if (!currentUser.require().isAdmin()) {
            throw new DriveException(ErrorCode.FORBIDDEN, "只有管理员能操作");
        }
    }
}