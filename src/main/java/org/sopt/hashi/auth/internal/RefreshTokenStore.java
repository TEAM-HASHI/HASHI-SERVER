package org.sopt.hashi.auth.internal;

import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 저장소(Redis). 사용자당 현재 토큰 1개를 TTL과 함께 보관한다.
 * 재발급 시 회전(rotate)하고, 이미 회전된 과거 토큰이 재사용되면 세션 전체를 무효화한다.
 */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtProperties properties;

    public RefreshTokenStore(RedisTemplate<String, Object> redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 로그인 시 현재 리프레시 토큰을 저장한다(TTL = refresh 만료시간). */
    public void save(Long userId, String refreshToken) {
        redisTemplate.opsForValue().set(key(userId), refreshToken, properties.refreshTokenTtl());
    }

    /**
     * 재발급 회전. 제시된 토큰이 현재 토큰과 일치할 때만 새 토큰으로 교체한다.
     * 불일치(이미 회전된 토큰 재사용)면 세션을 무효화하고 TOKEN_REUSE_DETECTED를 던진다.
     */
    public void rotate(Long userId, String presentedToken, String newToken) {
        Object currentToken = redisTemplate.opsForValue().get(key(userId));
        if (currentToken == null) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        if (!currentToken.equals(presentedToken)) {
            revoke(userId);
            throw new BusinessException(AuthErrorCode.TOKEN_REUSE_DETECTED);
        }
        save(userId, newToken);
    }

    /** 세션 무효화(탈퇴·재사용 감지 시). 리프레시 토큰을 삭제한다. */
    public void revoke(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
