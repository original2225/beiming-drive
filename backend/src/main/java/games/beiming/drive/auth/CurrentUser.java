package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从请求里取出当前登录用户。
 * Controller 用 CurrentUser.require() 拿，未登录直接抛 UNAUTHORIZED。
 */
@Component
public class CurrentUser {

    public record Auth(long userId, String role, String tokenId) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }

    public Auth require() {
        Auth a = optional();
        if (a == null) {
            throw new DriveException(ErrorCode.UNAUTHORIZED);
        }
        return a;
    }

    public Auth optional() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        Object v = attrs.getRequest().getAttribute(AuthInterceptor.ATTR_PRINCIPAL);
        return v instanceof Auth a ? a : null;
    }
}