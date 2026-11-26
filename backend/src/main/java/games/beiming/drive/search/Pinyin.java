package games.beiming.drive.search;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 汉字转拼音，用于支持「打 bgjl 找到 北冥整合包」。
 *
 * <p>三种形态都要，缺一个就有一类输入搜不到：
 * <ul>
 *   <li>{@link #full} 全拼连写：beimingzhenhebao</li>
 *   <li>{@link #initials} 首字母：bmzhb（用户最常打这个）</li>
 *   <li>{@link #spaced} 逐字分开：bei ming zheng he bao（支持只打中间几个字）</li>
 * </ul>
 *
 * <p>拼不出来的字符（英文、数字、标点）原样保留，这样 "v2.4" 这类也能被同时索引。
 * 与业务无关，可整类复用。
 */
public final class Pinyin {

    private static final HanyuPinyinOutputFormat FMT = new HanyuPinyinOutputFormat();

    static {
        FMT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FMT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FMT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private Pinyin() {
    }

    /** 每个字符的拼音（多音字取第一个读音）；非汉字返回 null */
    private static String syllable(char c) {
        try {
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, FMT);
            return (arr == null || arr.length == 0) ? null : arr[0];
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            return null;
        }
    }

    public static String full(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (char c : text.toCharArray()) {
            String s = syllable(c);
            if (s != null) sb.append(s);
            else if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public static String initials(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            String s = syllable(c);
            if (s != null) sb.append(s.charAt(0));
            else if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public static String spaced(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 4);
        for (char c : text.toCharArray()) {
            String s = syllable(c);
            if (s != null) sb.append(s).append(' ');
            else if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString().trim();
    }
}