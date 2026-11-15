package org.sopt.hashi.auth.internal.token;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 보안 크리티컬: 이미 회전된(사용된) 리프레시 토큰이 재사용되면 세션을 무효화(TOKEN_REUSE_DETECTED)해야 한다.
 * 로그아웃(revoke)은 주체의 리프레시 키를 삭제해 이후 회전이 세션 부재(REFRESH_TOKEN_NOT_FOUND)로 거부되게 해야 한다.
 */
@SuppressWarnings("unchecked")
class RefreshTokenStoreTest {

    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    private final JwtProperties properties = new JwtProperties(
            "test-secret-key-must-be-at-least-32-bytes-long",
            Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(30));
    private final RefreshTokenStore store = new RefreshTokenStore(redisTemplate, properties);

    private void givenRotateScriptReturns(Long result) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .willReturn(result);
    }

    @Test
    @DisplayName("이미 회전된 토큰이 재사용되면(-1) TOKEN_REUSE_DETECTED로 세션을 무효화한다")
    void 재사용_감지() {
        givenRotateScriptReturns(-1L);

        assertThatThrownBy(() -> store.rotate("ROLE_USER", 7L, "used", "new"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.TOKEN_REUSE_DETECTED);
    }

    @Test
    @DisplayName("현재 저장된 토큰이 없으면(0) REFRESH_TOKEN_NOT_FOUND")
    void 세션_부재() {
        givenRotateScriptReturns(0L);

        assertThatThrownBy(() -> store.rotate("ROLE_USER", 7L, "old", "new"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    @Test
    @DisplayName("revoke는 주체(역할+id)의 리프레시 키를 삭제한다 — 이후 회전은 세션 부재(0)로 거부된다")
    void 세션_폐기() {
        store.revoke("ROLE_USER", 7L);

        verify(redisTemplate).delete("auth:refresh:ROLE_USER:7");
    }
}
