package games.beiming.drive.fs;

import games.beiming.drive.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 右侧栏的两个模块：下载记录、收藏夹。
 *
 * 都挂在 /api/v1/me 下，语义是「和我有关的东西」，不是文件操作本身。
 * 文件列表里点星标也走这里（/star/{id}），因为那个动作属于"我"，
 * 不属于文件 —— 同一份文件别人可能没收藏。
 */
@RestController
@RequestMapping("/api/v1/me")
public class UserFileMarksController {

    private final UserFileMarksService marks;

    public UserFileMarksController(UserFileMarksService marks) {
        this.marks = marks;
    }

    /* ==================== 下载记录 ==================== */

    /** 侧栏用，默认取最近 20 条 */
    @GetMapping("/downloads")
    public ApiResponse<List<UserFileMarksService.DownloadEntry>> downloads(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(marks.downloadHistory(limit));
    }

    @DeleteMapping("/downloads")
    public ApiResponse<Map<String, Integer>> clearDownloads() {
        return ApiResponse.ok(Map.of("deleted", marks.clearDownloadHistory()));
    }

    @DeleteMapping("/downloads/{id}")
    public ApiResponse<Void> deleteDownload(@PathVariable long id) {
        marks.deleteDownloadEntry(id);
        return ApiResponse.ok(null);
    }

    /* ==================== 收藏 ==================== */

    @GetMapping("/stars")
    public ApiResponse<List<UserFileMarksService.StarEntry>> stars(
            @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(marks.stars(limit));
    }

    @PostMapping("/star/{nodeId}")
    public ApiResponse<Void> star(@PathVariable long nodeId) {
        marks.star(nodeId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/star/{nodeId}")
    public ApiResponse<Void> unstar(@PathVariable long nodeId) {
        marks.unstar(nodeId);
        return ApiResponse.ok(null);
    }

    /**
     * 批量问「这些文件里我收藏了哪些」。
     * 文件列表一屏几十行，逐行发请求会把后端打满，所以一次问完。
     * 用 POST 因为 id 列表可能很长，塞 query 会超 URL 长度。
     */
    public record StarQuery(List<Long> ids) {}

    @PostMapping("/stars/in")
    public ApiResponse<List<Long>> starredIn(@RequestBody StarQuery q) {
        return ApiResponse.ok(marks.starredIn(q.ids()));
    }
}