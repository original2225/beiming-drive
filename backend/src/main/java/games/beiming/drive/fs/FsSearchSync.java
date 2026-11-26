package games.beiming.drive.fs;

import games.beiming.drive.search.SearchDoc;
import games.beiming.drive.search.SearchIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把云盘的节点变动同步到搜索索引。
 *
 * <p>这是**唯一**一处把「业务字段」翻译成「通用文档」的地方：
 * 云盘关心的是 name/path/kind/size，索引只认 title/extra/payload。
 * 换项目时照着这个类写一份自己的翻译即可，search 包不动。
 *
 * <p>一致性策略是「写时同步」而不是定时重建 —— 文件名操作频率低，
 * 每次改名/移动就地更新索引最省心；启动时再做一次全量对齐兜住历史数据。
 */
@Service
public class FsSearchSync {

    private static final Logger log = LoggerFactory.getLogger(FsSearchSync.class);

    private final SearchIndex index;

    public FsSearchSync(SearchIndex index) {
        this.index = index;
    }

    /** 节点 → 可检索文档。回收站里的不进索引（和原来 LIKE 搜索的行为保持一致）。 */
    public static SearchDoc toDoc(FileService.Node n) {
        Map<String, String> extra = new HashMap<>();
        // 只放路径：用户搜「文档库/关于」这类组合词也能命中。
        // 注意不要把 kind（「文件」/「文件夹」）塞进来 —— 那样搜「文件」
        // 会把库里每一条都命中，等于没筛。kind 只放 payload 供前端展示。
        extra.put("path", n.path() == null ? "" : n.path());

        Map<String, Object> payload = new HashMap<>();
        payload.put("__title", n.name());
        payload.put("__scope", String.valueOf(n.ownerId()));
        payload.put("id", n.id());
        payload.put("name", n.name());
        payload.put("path", n.path());
        payload.put("kind", n.kind());
        payload.put("size", n.size());
        payload.put("mime", n.mime());
        payload.put("updatedAt", n.updatedAt() == null ? null : n.updatedAt().toString());
        return new SearchDoc(String.valueOf(n.id()), n.name(),
                String.valueOf(n.ownerId()), extra, payload);
    }

    public void put(FileService.Node n) {
        if (n == null) return;
        if ("TRASHED".equals(n.status())) {
            index.delete(String.valueOf(n.id()));
            return;
        }
        index.upsert(toDoc(n));
    }

    public void putAll(List<FileService.Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        index.upsertAll(nodes.stream().map(FsSearchSync::toDoc).collect(Collectors.toList()));
    }

    public void remove(long id) {
        index.delete(String.valueOf(id));
    }

    /** 按 owner 隔离域搜索，返回命中文档（调用方从 payload 里取回业务数据） */
    public List<games.beiming.drive.search.SearchIndex.Hit> search(String ownerScope, String keyword, int limit) {
        return index.search(keyword, ownerScope, limit);
    }

    public int docCount() {
        return index.docCount();
    }

    /** 启动时全量重建一次，兜住「索引目录丢了/版本升级/历史数据」的情况 */
    public void rebuildAll(List<FileService.Node> allLiveNodes) {
        index.deleteAll();
        if (allLiveNodes != null && !allLiveNodes.isEmpty()) putAll(allLiveNodes);
        log.info("搜索索引重建完成，共 {} 条", index.docCount());
    }
}