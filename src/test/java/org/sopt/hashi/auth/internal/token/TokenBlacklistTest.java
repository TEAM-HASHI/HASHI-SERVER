package org.sopt.hashi.auth.internal.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 보안 크리티컬: 탈퇴 회원은 회원 단위 표식으로 차단되고, 표식은 리프레시 만료시간보다 오래 살아야 한다
 * (탈퇴 전후에 발급된 어떤 토큰보다 오래). 탈퇴가 롤백되면 표식만 되돌려 회원이 묶이지 않아야 한다.
 */
@SuppressWarnings("unchecked")
class TokenBlacklistTest {

    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    private final ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    private final JwtProperties properties = new JwtProperties(
            "test-secret-key-must-be-at-least-32-bytes-long",
            Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(30));
    private final TokenBlacklist blacklist = new TokenBlacklist(redisTemplate, properties);

    @Test
    @DisplayName("차단은 회원 단위 키에 리프레시 만료시간보다 긴 TTL로 표식을 남긴다")
    void 차단_TTL() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        blacklist.blockUser(7L);

        verify(valueOperations).set("auth:blacklist:user:7", "withdrawn",
                Duration.ofDays(14).plus(TokenBlacklist.TTL_MARGIN));
    }

    @Test
    @DisplayName("표식이 있으면 차단, 없으면 통과로 판정한다")
    void 차단_판정() {
        given(redisTemplate.hasKey("auth:blacklist:user:7")).willReturn(true);
        given(redisTemplate.hasKey("auth:blacklist:user:8")).willReturn(false);

        assertThat(blacklist.isUserBlocked(7L)).isTrue();
        assertThat(blacklist.isUserBlocked(8L)).isFalse();
    }

    @Test
    @DisplayName("차단 해제는 회원 단위 키를 삭제한다 — 탈퇴 롤백 시 회원이 묶이지 않는다")
    void 차단_해제() {
        blacklist.unblockUser(7L);

        verify(redisTemplate).delete("auth:blacklist:user:7");
    }
}
