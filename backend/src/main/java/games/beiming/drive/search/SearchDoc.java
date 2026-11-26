package games.beiming.drive.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条可被检索的文档。字段全部是「业务无关的通用键值」，
 * 由调用方（云盘 / 官网 / 其他项目）自己决定放什么。
 *
 * <p>约定：{@code id} 唯一且稳定（用主键），{@code title} 是主检索字段，
 * {@code extra} 是附加的可检索文本（比如路径、正文摘要、标签），
 * {@code payload} 是命中后要原样带回去给前端的业务数据（不进索引）。
 *
 * <p>换项目只需换这三个字段的来源，索引层代码一行不用改。
 */
public record SearchDoc(
        String id,
        String title,
        String scope,
        Map<String, String> extra,
        Map<String, Object> payload
) {
    public SearchDoc {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
        title = title == null ? "" : title;
        // 注意：不能用 Map.copyOf —— 它遇到 null 值直接抛 NPE。
        // 业务数据里 mime/updatedAt 这类字段天然可能为空，必须容忍 null。
        extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** 无隔离域的文档（单租户场景，比如官网全站搜索） */
    public SearchDoc(String id, String title, Map<String, String> extra, Map<String, Object> payload) {
        this(id, title, null, extra, payload);
    }
}