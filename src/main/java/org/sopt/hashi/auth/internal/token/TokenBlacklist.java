package org.sopt.hashi.auth.internal.token;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
    /** 표식 값 = 사유 + 요청별 고유값. 사유는 Redis에서 바로 읽히고, 고유값은 다른 요청의 표식을 지우지 않기 위한 구분값이다. */
    private static final String WITHDRAWN_MARKER_PREFIX = "withdrawn:";
    /**
     * 저장값이 이 요청의 표식일 때만 지운다(원자 비교·삭제). 같은 회원의 탈퇴 요청 두 개가 겹쳐 앞 요청의 늦은 롤백 정리가
     * 뒤 요청이 성공 후 쓴 표식을 지우는 일을 막는다. ARGV는 저장 시와 같은 값 직렬화기로 넘어가 비교가 일치한다.
     */
    private static final RedisScript<Long> UNBLOCK_IF_MARKER_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
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

    /** 회원의 모든 토큰을 차단하고 이 요청의 표식 값을 돌려준다(TTL = 리프레시 만료시간 + 여유). */
    public String blockUser(Long userId) {
        String marker = WITHDRAWN_MARKER_PREFIX + UUID.randomUUID();
        redisTemplate.opsForValue().set(key(userId), marker, properties.refreshTokenTtl().plus(TTL_MARGIN));
        return marker;
    }

    /** 이 요청이 등록한 표식일 때만 차단을 되돌린다 — 탈퇴 트랜잭션이 롤백돼 회원이 유지될 때 쓴다. */
    public void unblockUser(Long userId, String marker) {
        redisTemplate.execute(UNBLOCK_IF_MARKER_SCRIPT, List.of(key(userId)), marker);
    }

    public boolean isUserBlocked(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
