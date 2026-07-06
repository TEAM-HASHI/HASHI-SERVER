package org.sopt.hashi.auth.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정값 바인딩. secret은 환경변수(JWT_SECRET)로 주입한다(HS256 — 32바이트 이상).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl,
                            Duration onboardingTokenTtl) {
}
