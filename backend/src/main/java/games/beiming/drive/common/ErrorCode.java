package games.beiming.drive.common;

/**
 * 业务错误码。前端按 code 分流展示，不解析 message 文本。
 * 40000 段：请求错误      41000 段：认证与会话
 * 42000 段：权限          43000 段：资源不存在/状态冲突
 * 45000 段：上传与文件分发  50000 段：服务端
 */
public enum ErrorCode {
    OK(0, "success"),

    BAD_REQUEST(40000, "请求参数不合法"),
    NOT_FOUND(43000, "资源不存在"),
    CONFLICT(43001, "目标已存在"),
    STATE_CONFLICT(43002, "当前状态不允许该操作"),
    FORBIDDEN(42000, "没有权限"),
    QUOTA_EXCEEDED(42001, "存储配额不足"),

    UNAUTHORIZED(41000, "未登录或登录已失效"),
    BAD_CREDENTIALS(41001, "邮箱或密码不正确"),
    ACCOUNT_DISABLED(41002, "账号已被禁用"),

    UPLOAD_SESSION_GONE(45000, "上传会话已失效"),
    CHUNK_OUT_OF_RANGE(45001, "分片序号越界"),
    STORAGE_ERROR(45002, "存储层写入失败"),

    REMOTE_FETCH_FAILED(46000, "远程地址不可达或拒绝访问"),

    INTERNAL(50000, "服务端错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}