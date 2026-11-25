package games.beiming.drive.common;

/**
 * 业务异常。Service 层抛它，全局处理器统一转成 ApiResponse。
 */
public class DriveException extends RuntimeException {

    private final ErrorCode errorCode;

    public DriveException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public DriveException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}