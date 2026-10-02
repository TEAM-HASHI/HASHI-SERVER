package org.sopt.hashi.auth.internal.token;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 토큰 블랙리스트(Redis). 액세스 토큰은 무상태 JWT라 삭제할 수 없으므로 차단 표식을 두고,
 * {@code JwtAuthenticationFilter}와 재발급이 대조해 거부한다(auth.md §3).
 * 지금은 회원 단위 차단만 있다(사유: 탈퇴). TTL은 리프레시 만료시간에 여유를 더한 값이다 — 탈퇴 처리 중 다른 기기에서
 * 로그인해 새 리프레시 토큰을 받았더라도 재발급이 이 표식을 대조하므로, 탈퇴 전후에 발급된 어떤 토큰도 표식보다 오래 살 수 없다.
 * 토큰 단위 차단(로그아웃 등)이 필요해지면 jti 기준 키를 가진 메서드를 나란히 추가한다.
 */
@Component
public class TokenBlacklist {

    private static final String KEY_PREFIX = "auth:blacklist:user:";
    private static final String WITHDRAWN = "withdrawn";
    /**
     * 표식 기록과 탈퇴 커밋 사이에 끼어든 로그인은 표식보다 몇 ms 늦게 만료되는 리프레시 토큰을 받을 수 있다.
     * 그 토큰이 표식 만료 뒤 잠깐 되살아나지 않도록 TTL에 그 지연을 충분히 덮는 여유를 더한다.
     */
    static final Duration TTL_MARGIN = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtProperties properties;

    public TokenBlacklist(RedisTemplate<String, Object> redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 회원의 모든 토큰을 차단한다(TTL = 리프레시 만료시간 + 여유). 현재 사유는 탈퇴뿐이라 표식 값으로 남긴다. */
    public void blockUser(Long userId) {
        redisTemplate.opsForValue().set(key(userId), WITHDRAWN, properties.refreshTokenTtl().plus(TTL_MARGIN));
    }

    /** 차단을 되돌린다 — 탈퇴 트랜잭션이 롤백돼 회원이 유지될 때 쓴다. */
    public void unblockUser(Long userId) {
        redisTemplate.delete(key(userId));
    }

    public boolean isUserBlocked(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
