package games.beiming.drive.remote;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/remote")
public class RemoteController {

    private final RemoteDownloadService service;
    private final CurrentUser currentUser;

    public RemoteController(RemoteDownloadService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public record CreateReq(@NotBlank String url,
                            @Size(max = 255) String filename,
                            Long targetParentId,
                            Integer workers) {
    }

    @PostMapping("/jobs")
    public ApiResponse<RemoteDownloadService.Job> create(@Valid @RequestBody CreateReq req) {
        long uid = currentUser.require().userId();
        RemoteDownloadService.Job job = service.create(uid, req.url(), req.filename(),
                req.targetParentId(), req.workers());
        service.start(uid, job.id());
        return ApiResponse.ok(service.byId(uid, job.id()));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<RemoteDownloadService.Job>> list() {
        return ApiResponse.ok(service.list(currentUser.require().userId()));
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<RemoteDownloadService.Job> one(@PathVariable String id) {
        return ApiResponse.ok(service.byId(currentUser.require().userId(), id));
    }

    @PostMapping("/jobs/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String id) {
        service.cancel(currentUser.require().userId(), id);
        return ApiResponse.ok(null);
    }
}