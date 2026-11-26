package games.beiming.drive.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于嵌入式 Lucene 的搜索索引。**与业务完全解耦**：只认 {@link SearchDoc}，
 * 不知道任何云盘/官网概念，整个 search 包可以原样复制到别的项目。
 *
 * <p>三件核心事：
 * <ol>
 *   <li>索引写入：title 走分词（中英混合），拼音另建三个字段（全拼/首字母/逐字）</li>
 *   <li>查询解析：把用户输入拆成「原文 + 拼音」两路，原文走模糊/前缀，拼音走前缀</li>
 *   <li>加权：标题完全等于关键词 > 标题以关键词开头 > 标题包含 > 拼音命中</li>
 * </ol>
 *
 * <p>线程安全：IndexWriter 自身线程安全；读用 DirectoryReader 每次开新的
 * （数据量小，开销可忽略），避免继续持有已删除的 reader。
 */
public class SearchIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    public static final String F_ID = "id";
    public static final String F_TITLE = "title";
    /** 标题原文（不分词），用于「完全相同」加权和前缀匹配 */
    public static final String F_TITLE_RAW = "title_raw";
    public static final String F_PINYIN_FULL = "py_full";
    public static final String F_PINYIN_INITIALS = "py_initials";
    public static final String F_PINYIN_SPACED = "py_spaced";
    public static final String F_PAYLOAD = "payload";
    /** 隔离域：多租户/多空间场景下过滤用；单租户项目留空即可 */
    public static final String F_SCOPE = "scope";
    public static final String EXTRA_PREFIX = "x_";

    private final Path dir;
    private final Analyzer analyzer = new MixedCjkAnalyzer();
    private final IndexWriter writer;
    private final Object writeLock = new Object();

    public SearchIndex(Path dirPath) throws IOException {
        this.dir = dirPath;
        Files.createDirectories(dirPath);
        Directory d = FSDirectory.open(dirPath);
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        // CREATE_OR_APPEND：重启不重建索引，已有数据继续用；索引损坏才需要手工删目录
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(d, cfg);
        this.writer.commit();
        log.info("search index ready at {} (docs={})", dirPath, docCount());
    }

    // ---------------- 写 ----------------

    /** 新增或覆盖一条（按 id 幂等） */
    public void upsert(SearchDoc doc) {
        synchronized (writeLock) {
            try {
                writer.updateDocument(new Term(F_ID, doc.id()), toLucene(doc));
                writer.commit();
            } catch (IOException e) {
                throw new IllegalStateException("索引写入失败: " + doc.id(), e);
            }
        }
    }

    public void upsertAll(List<SearchDoc> docs) {
        if (docs == null || docs.isEmpty()) return;
        synchronized (writeLock) {
            try {
                for (SearchDoc d : docs) writer.updateDocument(new Term(F_ID, d.id()), toLucene(d));
                writer.commit();
            } catch (IOException e) {
                throw new IllegalStateException("批量索引写入失败", e);
            }
        }
    }

    public void delete(String id) {
        synchronized (writeLock) {
            try {
                writer.deleteDocuments(new Term(F_ID, id));
                writer.commit();
            } catch (IOException e) {
                throw new IllegalStateException("索引删除失败: " + id, e);
            }
        }
    }

    public void deleteAll() {
        synchronized (writeLock) {
            try {
                writer.deleteAll();
                writer.commit();
            } catch (IOException e) {
                throw new IllegalStateException("清空索引失败", e);
            }
        }
    }

    private Document toLucene(SearchDoc d) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, d.id(), Field.Store.YES));

        String title = d.title();
        // 分词字段：中英混合切分，用于「包含」类匹配
        doc.add(new TextField(F_TITLE, title, Field.Store.NO));
        // 原文字段：整串存一份不分词，用于完全相同/前缀/模糊
        doc.add(new StringField(F_TITLE_RAW, title.toLowerCase(), Field.Store.NO));

        // 拼音三形态：都用 StringField（整串一个 token），查的时候用前缀/通配
        doc.add(new StringField(F_PINYIN_FULL, Pinyin.full(title), Field.Store.NO));
        doc.add(new StringField(F_PINYIN_INITIALS, Pinyin.initials(title), Field.Store.NO));
        doc.add(new StringField(F_PINYIN_SPACED, Pinyin.spaced(title), Field.Store.NO));

        // 附加文本：每个 key 一个字段，单字+双字切分
        for (Map.Entry<String, String> e : d.extra().entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            doc.add(new TextField(EXTRA_PREFIX + e.getKey(), e.getValue(), Field.Store.NO));
        }

        // payload 序列化成 JSON 存起来，命中后原样带回
        doc.add(new StoredField(F_PAYLOAD, Json.write(d.payload())));
        // scope 用 StringField 且不存：只做过滤，不进结果
        if (d.scope() != null && !d.scope().isBlank()) {
            doc.add(new StringField(F_SCOPE, d.scope(), Field.Store.NO));
        }
        return doc;
    }

    // ---------------- 读 ----------------

    public int docCount() {
        try (DirectoryReader r = DirectoryReader.open(writer)) {
            return r.numDocs();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 搜索结果：命中的文档 + 相关度分 */
    public record Hit(SearchDoc doc, float score) {
    }

    /**
     * 搜索。空的查询串返回空列表（由调用方决定要不要回落到列目录）。
     *
     * @param q     用户输入，可为中文/英文/拼音/混合
     * @param limit 最多返回多少条
     */
    public List<Hit> search(String q, int limit) {
        return search(q, null, limit);
    }

    /**
     * 带隔离域的搜索：{@code scope} 非空时只返回该域的文档。
     * 单租户项目（比如官网全站搜索）传 null 即可。
     */
    public List<Hit> search(String q, String scope, int limit) {
        String kw = q == null ? "" : q.trim();
        if (kw.isEmpty()) return List.of();

        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            // 两段式：先精确查（含拼音/前缀），一条都没命中才降级到容错查。
            // 为什么不分两路一起 SHOULD 累加：容错会把无关文件一并带出来
            // （搜「文档」把「完全无关的另一个文件」也带出），降级式能保证
            // 「有精确结果时结果干净」，只在真的什么都没搜到时兜底。
            Query exactQuery = buildQuery(kw, false);
            TopDocs top = exactQuery == null
                    ? null
                    : searcher.search(wrapScope(exactQuery, scope), Math.max(1, limit));

            if (top == null || top.scoreDocs.length == 0) {
                Query fuzzyQuery = buildQuery(kw, true);
                if (fuzzyQuery == null) return List.of();
                top = searcher.search(wrapScope(fuzzyQuery, scope), Math.max(1, limit));
            }

            List<Hit> out = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = searcher.storedFields().document(sd.doc);
                out.add(new Hit(toDoc(d), sd.score));
            }
            return out;
        } catch (IOException e) {
            log.warn("搜索失败 q={}: {}", kw, e.toString());
            return List.of();
        }
    }

    /** 把隔离域条件包在查询外面 */
    private static Query wrapScope(Query q, String scope) {
        if (scope == null || scope.isBlank()) return q;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(q, BooleanClause.Occur.MUST);
        b.add(new TermQuery(new Term(F_SCOPE, scope)), BooleanClause.Occur.FILTER);
        return b.build();
    }

    private SearchDoc toDoc(Document d) {
        String id = d.get(F_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) Json.read(d.get(F_PAYLOAD), Map.class);
        return new SearchDoc(id, String.valueOf(payload.getOrDefault("__title", "")),
                String.valueOf(payload.getOrDefault("__scope", "")), Map.of(), payload);
    }

    /**
     * 组装查询。核心思路：把一句话拆成多路，命中任意一路都算，
     * 但相关性按「越精确越靠前」加权。
     */
    private Query buildQuery(String kw, boolean withFuzzy) {
        String lower = kw.toLowerCase();
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        boolean any = false;

        // 1) 标题完全相同（最高权重）
        BooleanQuery.Builder exact = new BooleanQuery.Builder();
        exact.add(new TermQuery(new Term(F_TITLE_RAW, lower)), BooleanClause.Occur.SHOULD);
        root.add(new BoostQuery(exact.build(), 30f), BooleanClause.Occur.SHOULD);
        any = true;

        // 2) 标题以关键词开头
        if (lower.length() >= 1) {
            BooleanQuery.Builder prefix = new BooleanQuery.Builder();
            prefix.add(new WildcardQuery(new Term(F_TITLE_RAW, lower + "*")), BooleanClause.Occur.SHOULD);
            root.add(new BoostQuery(prefix.build(), 12f), BooleanClause.Occur.SHOULD);
        }

        // 3) 标题包含
        //    关键：查询词有 2 字以上时**只丢双字 token**，不放单字。
        //    否则搜「文档」会被拆成 文/档/文档，单字「文」命中「文件」、单字「档」命中「文挡」，
        //    结果库里一半文件都被带出来（实测 53 条里返回 46 条）。单字只在搜索单个字时用。
        try {
            List<String> tokens = MixedCjkAnalyzer.tokens(lower);
            List<String> queryTokens = new ArrayList<>();
            for (String t : tokens) {
                if (t.codePointCount(0, t.length()) >= 2) queryTokens.add(t);
            }
            // 查询本身就只有一个字 → 只能靠单字
            if (queryTokens.isEmpty()) queryTokens.addAll(tokens);

            if (!queryTokens.isEmpty()) {
                BooleanQuery.Builder contain = new BooleanQuery.Builder();
                // 命中越多 token 分越高：每个 token 一个 SHOULD，天然按命中数累加
                for (String t : queryTokens) {
                    contain.add(new TermQuery(new Term(F_TITLE, t)), BooleanClause.Occur.SHOULD);
                }
                root.add(new BoostQuery(contain.build(), 6f), BooleanClause.Occur.SHOULD);
            }
        } catch (IOException ignored) {
            // 分词失败就跳过这一路
        }

        // 4) 拼音三路
        //    全拼/首字母：先用「前缀」拿最高分（用户按开头打最常见），
        //    再补一路「包含」，让 beiming 也能命中 guanyubeiming（关于北冥）。
        String py = lower.replaceAll("[^a-z0-9]", "");
        if (!py.isEmpty()) {
            root.add(new BoostQuery(new WildcardQuery(new Term(F_PINYIN_FULL, py + "*")), 6f),
                    BooleanClause.Occur.SHOULD);
            root.add(new BoostQuery(new WildcardQuery(new Term(F_PINYIN_INITIALS, py + "*")), 6f),
                    BooleanClause.Occur.SHOULD);
            root.add(new BoostQuery(new WildcardQuery(new Term(F_PINYIN_FULL, "*" + py + "*")), 3f),
                    BooleanClause.Occur.SHOULD);
            root.add(new BoostQuery(new WildcardQuery(new Term(F_PINYIN_INITIALS, "*" + py + "*")), 2.5f),
                    BooleanClause.Occur.SHOULD);
            // 逐字形态是「bei ming zheng he bao」，用户打 "zheng" 这种中间字也要能中
            root.add(new BoostQuery(new WildcardQuery(new Term(F_PINYIN_SPACED, "*" + py + "*")), 3f),
                    BooleanClause.Occur.SHOULD);
        }

        // 5) 容错：只在「精确路一条都没命中」时才会被调用（见 search()）。
        //    **只对长度 ≥ 2 的双字 token** 做模糊。
        //    为什么这么保守：
        //      · 单字做模糊会把「整合」模糊成「北冥」「文档」等大量无关双字 → 噪音爆炸
        //        （实测 53 条库里「整合」返回 46 条，等于没筛）
        //      · 长串整串做模糊不可能命中（Lucene 编辑距离上限 2）
        //    所以只拿双字组合做、只给 1.2 分：精确命中永远压过容错命中，
        //    但错一个字时还有兜底（「文挡」→ 命中「文档」）。
        if (withFuzzy) {
            try {
                List<String> fuzzyTokens = MixedCjkAnalyzer.tokens(lower);
                BooleanQuery.Builder fuzzy = new BooleanQuery.Builder();
                boolean anyFuzzy = false;
                for (String t : fuzzyTokens) {
                    // 容错只在查询「3 字及以上」时启用，且只对双字 token 做。
                    // 为什么两字不做：两字词允许 1 个字符差异 = 「约等于所有同首字的词」。
                    // 搜「文件」会把「文档」带出来（编辑距离 1），用户会觉得搜错了词。
                    // 而「文挡」这类两字错字，宁可搜不到、也不要搜出一堆近义无关项。
                    // 三字以上时单字错误的影响可被其余 token 稀释，兜底才有意义。
                    if (lower.codePointCount(0, lower.length()) < 3) break;
                    if (t.codePointCount(0, t.length()) < 2) continue;   // 单字不做容错
                    try {
                        fuzzy.add(new org.apache.lucene.search.FuzzyQuery(new Term(F_TITLE, t), 1),
                                BooleanClause.Occur.SHOULD);
                        anyFuzzy = true;
                    } catch (IllegalArgumentException ignored) {
                        // token 里有不支持模糊的字符，跳过这一个
                    }
                }
                if (anyFuzzy) {
                    root.add(new BoostQuery(fuzzy.build(), 1.2f), BooleanClause.Occur.MUST);
                }
            } catch (IOException ignored) {
                // 分词失败就不做容错
            }
        }

        return any ? root.build() : null;
    }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            writer.close();
        }
        analyzer.close();
    }

    /** 索引目录（给运维看/备份用） */
    public Path directory() {
        return dir;
    }
}