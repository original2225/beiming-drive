package games.beiming.drive.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 中英文混合分词器：中文按「单字 + 双字组合」切，英文/数字按词切并小写归一。
 *
 * <p>为什么不用 Lucene 自带的 {@code CJKBigramFilter}：它只在**成对**的汉字上出双字，
 * 单字不出（或只在末尾补一个），结果搜单个字（比如「包」）直接搜不到。
 * 文件名场景里单字搜索很常见，所以这里自己切，单字和双字都出。
 *
 * <p>为什么这样切：文件名普遍很短（"北冥整合包-v2.4.zip"），拿词库分词反而分不准
 * ——「整合包」可能被切成「整合」+「包」，「整合包」就搜不到了。
 * 单字 + 双字的组合能保证任意长度 ≥2 的连续汉字子串都命中，也不需要维护词典。
 *
 * <p>与业务无关：这层只处理「文本怎么变成词」，不知道任何云盘概念，
 * 换到别的项目（比如北冥官网）可以整包复用。
 */
public final class MixedCjkAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer src = new GramTokenizer();
        return new TokenStreamComponents(src, new LowerCaseFilter(src));
    }

    /** 把字符串拆成 token 列表，主要给单元测试和调试用 */
    public static List<String> tokens(String text) throws IOException {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        try (Analyzer a = new MixedCjkAnalyzer();
             TokenStream ts = a.tokenStream("f", text)) {
            CharTermAttribute t = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(t.toString());
            ts.end();
        }
        return out;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN
                || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA
                || s == Character.UnicodeScript.HANGUL;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) && !isCjk(c);
    }

    /**
     * 自写分词器。切分规则：
     * <ul>
     *   <li>连续汉字段：每个单字出一个 token，每对相邻双字也出一个</li>
     *   <li>连续字母/数字：整段出一个 token（如 "v2"、"4"、"pdf"）</li>
     *   <li>标点、空白：当分隔符丢弃</li>
     * </ul>
     * 单字和双字的 positionIncrement 都设 1，这样查询端用 BooleanQuery 的
     * SHOULD 累加时，「命中更多 token」自然得高分。
     */
    static final class GramTokenizer extends Tokenizer {

        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
        private final PositionIncrementAttribute posInc = addAttribute(PositionIncrementAttribute.class);

        private String text = "";
        private int consumed = 0;
        private int runStart = -1;
        private int runEnd = -1;
        /** 汉字段内下一个要处理的位置；到 runEnd 表示本段结束 */
        private int nextBigram = -1;
        /** 当前位置是否还没吐单字 */
        private boolean pendingSingle = true;

        @Override
        public void reset() throws IOException {
            super.reset();
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = input.read(buf)) != -1) sb.append(buf, 0, n);
            text = sb.toString();
            consumed = 0;
            runStart = -1;
            runEnd = -1;
            nextBigram = -1;
            pendingSingle = true;
        }

        @Override
        public boolean incrementToken() {
            while (true) {
                clearAttributes();

                // 1) 当前汉字段：同一位置上先出单字、再出双字
                //    必须按位置递增交替出。Lucene 要求 startOffset 单调不减，
                //    若先把单字全吐完再吐双字，双字的 start 会回到 0 → 直接抛
                //    "offsets must not go backwards"。
                if (runStart >= 0 && nextBigram < runEnd) {
                    int p = nextBigram;
                    if (pendingSingle) {
                        pendingSingle = false;
                        // append(CharSequence, start, end) 第三参是「结束位置」，不是长度
                        term.append(text, p, p + 1);
                        offset.setOffset(p, p + 1);
                        posInc.setPositionIncrement(1);
                        return true;
                    }
                    pendingSingle = true;   // 下一个位置从单字开始
                    nextBigram++;
                    if (p + 1 < runEnd) {
                        term.append(text, p, p + 2);
                        offset.setOffset(p, p + 2);
                        posInc.setPositionIncrement(1);
                        return true;
                    }
                    // 段尾只有一个字（或双字越界），跳过双字，回路上面的单字
                    continue;
                }
                // 2) 本段吐完
                if (runStart >= 0) {
                    runStart = -1;
                    runEnd = -1;
                    nextBigram = -1;
                }

                // 4) 找下一段
                if (consumed >= text.length()) return false;
                char c = text.charAt(consumed);

                if (isCjk(c)) {
                    int s = consumed;
                    while (consumed < text.length() && isCjk(text.charAt(consumed))) consumed++;
                    runStart = s;
                    runEnd = consumed;
                    nextBigram = s;
                    pendingSingle = true;
                    continue; // 回到 1) 开始吐
                }
                if (isWordChar(c)) {
                    int s = consumed;
                    while (consumed < text.length() && isWordChar(text.charAt(consumed))) consumed++;
                    term.append(text, s, consumed);
                    offset.setOffset(s, consumed);
                    posInc.setPositionIncrement(1);
                    return true;
                }
                consumed++; // 分隔符跳过
            }
        }

        @Override
        public void end() throws IOException {
            super.end();
            int finalOffset = text.length();
            offset.setOffset(finalOffset, finalOffset);
        }
    }
}