package org.sopt.hashi.auth.internal.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;

/**
 * 보안 크리티컬: 위·변조·만료·이질(우리 발급 아님) 토큰을 반드시 거부하는지 검증한다.
 */
class JwtProviderTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long";

    private JwtProvider providerWithAccessTtl(Duration accessTtl) {
        return new JwtProvider(new JwtProperties(SECRET, accessTtl,
                Duration.ofDays(14), Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("정상 액세스 토큰은 subjectId·role·type이 그대로 파싱된다(가용성 기준선)")
    void 정상_토큰_파싱() {
        JwtProvider provider = providerWithAccessTtl(Duration.ofMinutes(30));

        JwtProvider.JwtClaims claims = provider.parse(provider.createAccessToken(7L, AuthRoles.USER));
        assertThat(claims.role()).isEqualTo(AuthRoles.USER);
        assertThat(claims.subjectId()).isEqualTo(7L);
        assertThat(claims.isAccessToken()).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰은 EXPIRED_TOKEN으로 거부한다")
    void 만료_토큰_거부() {
        JwtProvider provider = providerWithAccessTtl(Duration.ofSeconds(-10)); // exp가 과거 → 즉시 만료

        assertThatThrownBy(() -> provider.parse(provider.createAccessToken(7L, AuthRoles.USER)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.EXPIRED_TOKEN);
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 INVALID_TOKEN으로 거부한다")
    void 형식오류_토큰_거부() {
        JwtProvider provider = providerWithAccessTtl(Duration.ofMinutes(30));

        assertThatThrownBy(() -> provider.parse("not.a.valid.jwt"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("다른 키로 서명된(위조) 토큰은 INVALID_TOKEN으로 거부한다")
    void 위조_서명_거부() {
        String forged = new JwtProvider(new JwtProperties(
                "another-secret-key-also-at-least-32-bytes-xx", Duration.ofMinutes(30),
                Duration.ofDays(14), Duration.ofMinutes(30)))
                .createAccessToken(7L, AuthRoles.USER);

        assertThatThrownBy(() -> providerWithAccessTtl(Duration.ofMinutes(30)).parse(forged))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("서명은 유효하나 role/type이 없는(우리 발급 아님) 토큰은 INVALID_TOKEN으로 거부한다")
    void 이질_토큰_거부() {
        // 우리 secret으로 서명됐지만 role·type 클레임이 없는 토큰 — 권한 매핑에서 새지 않도록 차단돼야 한다
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String noClaims = Jwts.builder().subject("7").signWith(key).compact();

        assertThatThrownBy(() -> providerWithAccessTtl(Duration.ofMinutes(30)).parse(noClaims))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
    }
}
