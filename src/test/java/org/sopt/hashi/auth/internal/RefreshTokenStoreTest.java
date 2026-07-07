package org.sopt.hashi.auth.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

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

        assertThatThrownBy(() -> store.rotate(7L, "used", "new"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.TOKEN_REUSE_DETECTED);
    }

    @Test
    @DisplayName("현재 저장된 토큰이 없으면(0) REFRESH_TOKEN_NOT_FOUND")
    void 세션_부재() {
        givenRotateScriptReturns(0L);

        assertThatThrownBy(() -> store.rotate(7L, "old", "new"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }
}
