package games.beiming.drive.share;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.fs.FileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/shares")
public class ShareController {

    private final ShareService shares;
    private final FileService files;
    private final CurrentUser currentUser;

    public ShareController(ShareService shares, FileService files, CurrentUser currentUser) {
        this.shares = shares;
        this.files = files;
        this.currentUser = currentUser;
    }

    public record CreateReq(long nodeId,
                            @Size(max = 64) String password,
                            Integer expireHours,
                            Integer maxDownloads) {
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateReq req) {
        ShareService.Share s = shares.create(currentUser.require().userId(), req.nodeId(),
                req.password(), req.expireHours(), req.maxDownloads());
        FileService.Node node = files.byId(s.ownerId(), s.nodeId());
        return ApiResponse.ok(Map.of("share", s, "node", node,
                "link", "/s/" + s.id()));
    }

    @GetMapping
    public ApiResponse<List<ShareService.Share>> mine() {
        return ApiResponse.ok(shares.listMine(currentUser.require().userId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable String id) {
        shares.revoke(currentUser.require().userId(), id);
        return ApiResponse.ok(null);
    }
}