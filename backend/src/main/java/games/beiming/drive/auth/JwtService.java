package games.beiming.drive.auth;

import games.beiming.drive.common.ErrorCode;
import games.beiming.drive.common.DriveException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验。
 * token 里只放 userId / role / jti。会话是否被吊销看 drv_session 表，
 * 这样登出不依赖 token 过期时间。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${drive.auth.jwt-secret}") String secret,
                      @Value("${drive.auth.token-ttl-hours:168}") long ttlHours) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("drive.auth.jwt-secret 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttl = Duration.ofHours(ttlHours);
    }

    public record Issued(String token, String tokenId, Instant expiresAt) {
    }

    public Issued issue(long userId, String role) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .subject(Long.toString(userId))
                .claim("role", role)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Issued(token, jti, exp);
    }

    public record Principal(long userId, String role, String tokenId) {
    }

    public Principal parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new Principal(Long.parseLong(c.getSubject()),
                    c.get("role", String.class), c.getId());
        } catch (JwtException | IllegalArgumentException e) {
            throw new DriveException(ErrorCode.UNAUTHORIZED);
        }
    }

    public Instant expiryOf(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload().getExpiration().toInstant();
        } catch (JwtException e) {
            throw new DriveException(ErrorCode.UNAUTHORIZED);
        }
    }
}