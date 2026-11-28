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

    /**
     * 当前请求的身份。role 一律以 {@link Role} 判定，别再在业务代码里
     * 写 {@code "ADMIN".equals(...)} —— 散在各处的字符串比较加角色时会漏改。
     */
    public record Auth(long userId, Role role, String tokenId) {

        /** 系统级权限（维护接口、全站数据、改别人配额） */
        public boolean isAdmin() { return role != null && role.isAdmin(); }

        /** 能不能指派/收回管理员 —— 只有超管 */
        public boolean canManageAdmins() { return role != null && role.canManageAdmins(); }

        public Role role() { return role == null ? Role.GUEST : role; }
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