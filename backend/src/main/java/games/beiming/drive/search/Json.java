package games.beiming.drive.search;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 极小的 JSON 工具：只用于 payload 的存取。单独抽出来是为了让 search 包
 * 不直接依赖调用方的序列化配置，复制到别的项目时不受影响。
 */
final class Json {

    private static final ObjectMapper M = new ObjectMapper();

    private Json() {
    }

    static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    static Object read(String s, Class<?> type) {
        if (s == null || s.isBlank()) return null;
        try {
            return M.readValue(s, type);
        } catch (Exception e) {
            return null;
        }
    }
}