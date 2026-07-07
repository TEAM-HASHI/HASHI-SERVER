package org.sopt.hashi.auth.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 설정값 바인딩. secret은 환경변수(JWT_SECRET)로 주입한다(HS256 — 32바이트 이상).
 * 필수값 누락·과소 secret은 부팅 시점에 즉시 실패시킨다(로그인 때 터지지 않도록).
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "JWT secret은 HS256용으로 32자 이상이어야 합니다") String secret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration onboardingTokenTtl) {
}
