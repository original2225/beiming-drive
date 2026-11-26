package games.beiming.drive.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索引擎行为测试。这些断言是「能力清单」：
 * 以后改分词/加权如果打破任何一条，测试会直接红掉。
 */
class SearchIndexTest {

    private Path dir;
    private SearchIndex idx;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("drive-search-test");
        idx = new SearchIndex(dir);
        idx.upsertAll(List.of(
                doc("1", "北冥整合包-v2.4.zip", "u1"),
                doc("2", "文档库", "u1"),
                doc("3", "关于北冥.md", "u1"),
                doc("4", "考研英语真题2020.pdf", "u1"),
                doc("5", "别人的秘密.txt", "u2")   // 另一个用户，用来验隔离
        ));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (idx != null) idx.close();
    }

    private static SearchDoc doc(String id, String title, String scope) {
        return new SearchDoc(id, title, scope, Map.of(), Map.of("__title", title));
    }

    private List<String> titles(String q, String scope) {
        return idx.search(q, scope, 20).stream()
                .map(h -> h.doc().title())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("中文子串能搜到（LIKE 能做到的部分不能退化）")
    void chineseSubstring() {
        assertTrue(titles("整合", "u1").contains("北冥整合包-v2.4.zip"));
        assertTrue(titles("文档", "u1").contains("文档库"));
    }

    @Test
    @DisplayName("打拼音全拼能找到中文文件名")
    void pinyinFull() {
        List<String> r = titles("beiming", "u1");
        assertTrue(r.contains("北冥整合包-v2.4.zip"), "打 beiming 应命中北冥整合包，实际: " + r);
        assertTrue(r.contains("关于北冥.md"), "打 beiming 也应命中关于北冥，实际: " + r);
    }

    @Test
    @DisplayName("打首字母能找到（用户最常这么打）")
    void pinyinInitials() {
        assertTrue(titles("bmzhb", "u1").contains("北冥整合包-v2.4.zip"));
        assertTrue(titles("gybm", "u1").contains("关于北冥.md"));
    }

    @Test
    @DisplayName("拼音打一半也能出结果（前缀匹配）")
    void pinyinPrefix() {
        assertTrue(titles("bei", "u1").stream().anyMatch(t -> t.contains("北冥")));
        // 「整合」首字母是 zh（zhh 是我一开始写错的，不是这个词的拼音）
        assertTrue(titles("zh", "u1").stream().anyMatch(t -> t.contains("整合")));
        // 全拼打一半
        assertTrue(titles("zheng", "u1").stream().anyMatch(t -> t.contains("整合")));
    }

    @Test
    @DisplayName("英文和数字能搜")
    void ascii() {
        assertTrue(titles("v2.4", "u1").contains("北冥整合包-v2.4.zip"));
        assertTrue(titles("2020", "u1").contains("考研英语真题2020.pdf"));
        assertTrue(titles("pdf", "u1").contains("考研英语真题2020.pdf"));
    }

    @Test
    @DisplayName("多字查询打错一个字的容错（LIKE 做不到）")
    void typo() {
        // 三字以上打错一个字 → 容错兜底生效
        List<String> r = titles("整合保", "u1");   // 「整合包」打成「整合保」
        assertTrue(r.contains("北冥整合包-v2.4.zip"), "三字错一个应该还能找到，实际: " + r);
    }

    @Test
    @DisplayName("两字查询不做容错，避免搜一个词蹦出另一个词")
    void noFuzzyForTwoChars() {
        // 「文件」不该被容错成「文档」：两字词允许 1 字差等于匹配所有同首字词
        List<String> r = titles("文件", "u1");
        assertFalse(r.contains("文档库"), "搜「文件」不该带出「文档库」，实际: " + r);
    }

    @Test
    @DisplayName("不同用户互相搜不到（隔离域生效）")
    void scopeIsolation() {
        assertFalse(titles("秘密", "u1").contains("别人的秘密.txt"), "u1 不该搜到 u2 的文件");
        assertTrue(titles("秘密", "u2").contains("别人的秘密.txt"));
    }

    @Test
    @DisplayName("相关度排序：完全匹配排在包含匹配前面")
    void ranking() {
        idx.upsertAll(List.of(
                doc("10", "文档", "u3"),
                doc("11", "文档库备份说明.md", "u3")
        ));
        List<String> r = titles("文档", "u3");
        assertEquals("文档", r.get(0), "完全等于关键词的应排第一，实际顺序: " + r);
    }

    @Test
    @DisplayName("删除后搜不到（删除要立即生效）")
    void deleteWorks() {
        idx.delete("1");
        assertFalse(titles("整合", "u1").contains("北冥整合包-v2.4.zip"));
    }

    @Test
    @DisplayName("同一 id 重复写是覆盖而不是重复")
    void upsertIsIdempotent() {
        int before = idx.docCount();
        idx.upsert(doc("1", "北冥整合包-v3.0.zip", "u1"));
        assertEquals(before, idx.docCount(), "同 id 覆盖不该增加文档数");
        assertTrue(titles("v3.0", "u1").contains("北冥整合包-v3.0.zip"));
        assertFalse(titles("v2.4", "u1").contains("北冥整合包-v2.4.zip"));
    }

    @Test
    @DisplayName("精确能命中时，不应把无关文件也带出来（噪音控制）")
    void noNoiseWhenExactHit() {
        idx.upsertAll(List.of(
                doc("20", "文档库", "u4"),
                doc("21", "完全无关的另一个文件.md", "u4"),
                doc("22", "第三份材料.txt", "u4")
        ));
        List<String> r = titles("文档", "u4");
        assertTrue(r.contains("文档库"));
        assertFalse(r.contains("完全无关的另一个文件.md"),
                "搜「文档」不该把无关文件带出来，实际: " + r);
        assertFalse(r.contains("第三份材料.txt"), "搜「文档」不该把「第三份材料」带出来，实际: " + r);
    }

    @Test
    @DisplayName("空查询返回空，不返回全量")
    void emptyQuery() {
        assertTrue(idx.search("", "u1", 20).isEmpty());
        assertTrue(idx.search("   ", "u1", 20).isEmpty());
        assertTrue(idx.search(null, "u1", 20).isEmpty());
    }

    @Test
    @DisplayName("分词器行为：中文切成单字+双字组合")
    void analyzerTokens() throws IOException {
        List<String> t = MixedCjkAnalyzer.tokens("北冥");
        assertTrue(t.contains("北"), "应含单字 北，实际: " + t);
        assertTrue(t.contains("冥"), "应含单字 冥，实际: " + t);
        assertTrue(t.contains("北冥"), "应含双字 北冥，实际: " + t);

        List<String> en = MixedCjkAnalyzer.tokens("Drive V2");
        assertTrue(en.contains("drive"), "英文应小写归一，实际: " + en);
    }
}