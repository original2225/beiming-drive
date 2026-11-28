package games.beiming.drive.auth;

import games.beiming.drive.common.DriveException;
import games.beiming.drive.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：只认 Authorization: Bearer <jwt>。
 * 公开路径在 WebConfig 里排除（/api/v1/auth/login|register、/s/** 分享访问）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_PRINCIPAL = "drive.principal";

    private final JwtService jwt;
    private final AuthService authService;

    public AuthInterceptor(JwtService jwt, AuthService authService) {
        this.jwt = jwt;
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String header = req.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        }
        // Range 请求 / 下载链接也要能带 query token，方便 <video> 之类直接用
        if (token == null || token.isEmpty()) {
            token = req.getParameter("access_token");
        }
        if (token == null || token.isEmpty()) {
            throw new DriveException(ErrorCode.UNAUTHORIZED);
        }

        JwtService.Principal p = jwt.parse(token);
        if (!authService.sessionAlive(p.tokenId())) {
            throw new DriveException(ErrorCode.UNAUTHORIZED);
        }
        req.setAttribute(ATTR_PRINCIPAL, new CurrentUser.Auth(
                p.userId(), games.beiming.drive.auth.Role.of(p.role()), p.tokenId()));
        return true;
    }
}