package org.sopt.hashi.auth.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 보안 크리티컬: 이미 소비됐거나 교체된(사용된) 온보딩 토큰은 재사용될 수 없어야 한다(1회용·replay 차단).
 */
@SuppressWarnings("unchecked")
class OnboardingTokenStoreTest {

    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    private final ValueOperations<String, Object> valueOps = Mockito.mock(ValueOperations.class);
    private final JwtProperties properties = new JwtProperties(
            "test-secret-key-must-be-at-least-32-bytes-long",
            Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(30));
    private final OnboardingTokenStore store = new OnboardingTokenStore(redisTemplate, properties);

    @Test
    @DisplayName("현재 저장된 토큰과 일치하면 통과한다")
    void 일치_통과() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("current-token");

        assertThatCode(() -> store.validate(555L, "current-token")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 교체된 옛 토큰을 제시하면(불일치) INVALID_ONBOARDING_TOKEN으로 거부한다")
    void 불일치_거부() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("current-token");

        assertThatThrownBy(() -> store.validate(555L, "old-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_ONBOARDING_TOKEN);
    }

    @Test
    @DisplayName("이미 소비됐거나 만료돼 토큰이 없으면 INVALID_ONBOARDING_TOKEN으로 거부한다")
    void 소비후_재사용_거부() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn(null);

        assertThatThrownBy(() -> store.validate(555L, "consumed-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_ONBOARDING_TOKEN);
    }
}
