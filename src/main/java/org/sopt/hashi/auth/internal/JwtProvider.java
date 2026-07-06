package org.sopt.hashi.auth.internal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Component;

/**
 * JWT 발급·검증. access/refresh를 type 클레임으로 구분하고, role 클레임에 권한(ROLE_USER 등)을 담는다.
 * 임시 권한 토큰(예: 온보딩 ROLE_ONBOARDING)도 같은 구조로 확장할 수 있다.
 */
@Component
public class JwtProvider {

    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";
    static final String TYPE_ONBOARDING = "onboarding";

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "type";

    private final SecretKey secretKey;
    private final JwtProperties properties;

    public JwtProvider(JwtProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String role) {
        return createToken(userId, role, TYPE_ACCESS, properties.accessTokenTtl());
    }

    /** 리프레시도 JWT로 발급(userId 식별용). 유효성은 RefreshTokenStore의 현재 토큰 대조로 판정한다. */
    public String createRefreshToken(Long userId, String role) {
        return createToken(userId, role, TYPE_REFRESH, properties.refreshTokenTtl());
    }

    /**
     * 온보딩 임시 토큰. 아직 userId가 없으므로 subject는 kakaoId다.
     * 유효성은 OnboardingTokenStore의 현재 토큰 대조로 판정하고, 온보딩 API에만 접근 가능하다(ROLE_ONBOARDING).
     */
    public String createOnboardingToken(Long kakaoId) {
        return createToken(kakaoId, AuthRoles.ONBOARDING, TYPE_ONBOARDING, properties.onboardingTokenTtl());
    }

    /** 서명·만료 검증 후 클레임을 파싱한다. 만료는 EXPIRED_TOKEN, 그 외 위변조·형식 오류는 INVALID_TOKEN. */
    public JwtClaims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String role = claims.get(CLAIM_ROLE, String.class);
            String type = claims.get(CLAIM_TYPE, String.class);
            if (role == null || type == null) {
                // 서명은 유효하나 우리 발급 구조가 아닌 토큰 — 필터의 권한 매핑에서 500이 나지 않도록 여기서 차단
                throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            }
            return new JwtClaims(Long.parseLong(claims.getSubject()), role, type);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(AuthErrorCode.EXPIRED_TOKEN, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN, e);
        }
    }

    private String createToken(Long userId, String role, String type, Duration ttl) {
        Date now = new Date();
        return Jwts.builder()
                // 만료(exp)가 초 단위라 같은 초에 발급된 토큰이 동일 문자열이 될 수 있다 —
                // 회전·재사용 감지가 무력화되지 않도록 jti로 토큰별 유일성을 보장한다
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(secretKey)
                .compact();
    }

    /** subject 파싱 값. access/refresh는 userId, onboarding 토큰은 kakaoId다. */
    public record JwtClaims(Long userId, String role, String type) {

        public boolean isAccessToken() {
            return TYPE_ACCESS.equals(type);
        }

        public boolean isRefreshToken() {
            return TYPE_REFRESH.equals(type);
        }

        public boolean isOnboardingToken() {
            return TYPE_ONBOARDING.equals(type);
        }
    }
}
