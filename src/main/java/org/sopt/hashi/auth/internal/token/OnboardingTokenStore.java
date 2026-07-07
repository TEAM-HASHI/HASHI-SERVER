package org.sopt.hashi.auth.internal.token;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 온보딩 임시 토큰 저장소(Redis). kakaoId당 현재 토큰 1개를 TTL과 함께 보관한다.
 * 1회용 — 온보딩 완료 시 consume으로 폐기되어 재사용할 수 없다(auth.md §4).
 */
@Component
public class OnboardingTokenStore {

    private static final String KEY_PREFIX = "auth:onboarding:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtProperties properties;

    public OnboardingTokenStore(RedisTemplate<String, Object> redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 카카오 인증 성공(비회원) 시 임시 토큰을 저장한다. 재로그인하면 새 토큰으로 교체된다. */
    public void save(Long kakaoId, String onboardingToken) {
        redisTemplate.opsForValue().set(key(kakaoId), onboardingToken, properties.onboardingTokenTtl());
    }

    /** 제시된 토큰이 현재 토큰과 일치하는지 검증한다(폐기·교체된 토큰 차단). */
    public void validate(Long kakaoId, String presentedToken) {
        Object currentToken = redisTemplate.opsForValue().get(key(kakaoId));
        if (currentToken == null || !currentToken.equals(presentedToken)) {
            throw new BusinessException(AuthErrorCode.INVALID_ONBOARDING_TOKEN);
        }
    }

    /** 온보딩 완료 시 1회용 토큰을 폐기한다. */
    public void consume(Long kakaoId) {
        redisTemplate.delete(key(kakaoId));
    }

    private String key(Long kakaoId) {
        return KEY_PREFIX + kakaoId;
    }
}
