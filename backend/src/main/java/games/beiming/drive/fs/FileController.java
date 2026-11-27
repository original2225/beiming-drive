package games.beiming.drive.fs;

import games.beiming.drive.auth.CurrentUser;
import games.beiming.drive.common.ApiResponse;
import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.storage.BlobStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService files;
    private final BlobStore blobs;
    private final CurrentUser currentUser;

    public FileController(FileService files, BlobStore blobs, CurrentUser currentUser) {
        this.files = files;
        this.blobs = blobs;
        this.currentUser = currentUser;
    }

    public record MkdirReq(Long parentId, @NotBlank @Size(max = 255) String name) {
    }

    public record RenameReq(@NotBlank @Size(max = 255) String name) {
    }

    public record MoveReq(long targetParentId) {
    }

    /** 列目录。parentId 省略 = 根。支持 orderBy=name|size|updatedAt 与 asc|desc。
     *  parentId 同样收 String 走 parseParentId，理由见 upload 的注释。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false, defaultValue = "name") String orderBy,
            @RequestParam(required = false, defaultValue = "asc") String direction) {
        long uid = currentUser.require().userId();
        Long pid = parseParentId(parentId);
        List<FileService.Node> items = files.list(uid, pid, orderBy, direction);
        FileService.Stats stats = files.stats(uid, pid);
        return ApiResponse.ok(Map.of(
                "items", items,
                "stats", stats
        ));
    }

    /** 存储构成：按类型统计 + 回收站占用。给「存储空间」页用。
     *  放在 /files 下而不是 /auth，因为它依赖的是文件树，不是用户表。 */
    @GetMapping("/storage")
    public ApiResponse<FileService.StorageBreakdown> storage() {
        long uid = currentUser.require().userId();
        return ApiResponse.ok(files.storageBreakdown(uid));
    }

    /** 全局搜索。返回命中条目及其所在目录路径。 */
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam String q) {
        long uid = currentUser.require().userId();
        List<FileService.Node> hits = files.search(uid, q, 200);
        return ApiResponse.ok(Map.of("items", hits, "total", hits.size()));
    }

    @GetMapping("/{id}")
    public ApiResponse<FileService.Node> one(@PathVariable long id) {
        return ApiResponse.ok(files.byId(currentUser.require().userId(), id));
    }

    @PostMapping("/mkdir")
    public ApiResponse<FileService.Node> mkdir(@Valid @RequestBody MkdirReq req) {
        return ApiResponse.ok(files.mkdir(currentUser.require().userId(), req.parentId(), req.name()));
    }

    /** 单请求直传（小文件走这条，大文件走分片上传）。
     *  parentId 可省略 —— 省略即落根目录。
     *  这里收 String 再手动解析，而不是直接 @RequestParam Long：前端若把 JS 的
     *  null 拼进 URL（`parentId=null`），Spring 会拿 "null" 去 Long.valueOf 并抛
     *  NumberFormatException，表面是 500。收字符串就能把 "null" / "" 一并当根目录。 */
    @PostMapping("/upload")
    public ApiResponse<FileService.Node> upload(
            @RequestParam(required = false) String parentId,
            @RequestParam("file") MultipartFile file) {
        long uid = currentUser.require().userId();
        if (file.isEmpty()) throw new DriveException(ErrorCode.BAD_REQUEST, "文件为空");
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) name = "unnamed";
        Long pid = parseParentId(parentId);
        try (var in = file.getInputStream()) {
            BlobStore.Stored st = blobs.put(in);
            return ApiResponse.ok(files.putFile(uid, pid, name,
                    file.getContentType(), st.sha256(), st.size()));
        } catch (IOException e) {
            throw new DriveException(ErrorCode.STORAGE_ERROR, e.getMessage());
        }
    }

    /** "null" / "" / 非数字 一律当根目录，别让脏参数变成 500 */
    private static Long parseParentId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new DriveException(ErrorCode.BAD_REQUEST, "parentId 不是合法数字：" + raw);
        }
    }

    @PatchMapping("/{id}/rename")
    public ApiResponse<FileService.Node> rename(@PathVariable long id,
                                                @Valid @RequestBody RenameReq req) {
        return ApiResponse.ok(files.rename(currentUser.require().userId(), id, req.name()));
    }

    @PostMapping("/{id}/move")
    public ApiResponse<FileService.Node> move(@PathVariable long id,
                                              @Valid @RequestBody MoveReq req) {
        return ApiResponse.ok(files.move(currentUser.require().userId(), id, req.targetParentId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> trash(@PathVariable long id) {
        files.trash(currentUser.require().userId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable long id) {
        files.restore(currentUser.require().userId(), id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purge(@PathVariable long id) {
        files.purge(currentUser.require().userId(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/trash")
    public ApiResponse<List<FileService.Node>> trash() {
        return ApiResponse.ok(files.trashList(currentUser.require().userId()));
    }
}