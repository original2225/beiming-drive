package games.beiming.drive.fs;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private final UploadService uploads;
    private final CurrentUser currentUser;

    public UploadController(UploadService uploads, CurrentUser currentUser) {
        this.uploads = uploads;
        this.currentUser = currentUser;
    }

    public record CreateReq(Long parentId,
                            @NotBlank @Size(max = 255) String name,
                            @Positive long totalSize,
                            Integer chunkSize) {
    }

    @PostMapping
    public ApiResponse<UploadService.Session> create(@Valid @RequestBody CreateReq req) {
        return ApiResponse.ok(uploads.create(currentUser.require().userId(), req.parentId(),
                req.name(), req.totalSize(), req.chunkSize()));
    }

    @PutMapping("/{id}/{idx}")
    public ApiResponse<Void> putChunk(@PathVariable String id,
                                      @PathVariable int idx,
                                      @RequestParam("chunk") MultipartFile chunk) throws IOException {
        try (var in = chunk.getInputStream()) {
            uploads.putChunk(currentUser.require().userId(), id, idx, in);
        }
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/status")
    public ApiResponse<UploadService.Status> status(@PathVariable String id) {
        return ApiResponse.ok(uploads.status(currentUser.require().userId(), id));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<UploadService.Completed> complete(@PathVariable String id) {
        return ApiResponse.ok(uploads.complete(currentUser.require().userId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> abort(@PathVariable String id) {
        uploads.abort(currentUser.require().userId(), id);
        return ApiResponse.ok(null);
    }
}