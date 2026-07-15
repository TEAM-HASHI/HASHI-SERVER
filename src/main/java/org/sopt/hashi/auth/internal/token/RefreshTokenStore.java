package org.sopt.hashi.auth.internal.token;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 저장소(Redis). 주체(역할+id)당 현재 토큰 1개를 TTL과 함께 보관한다.
 * 재발급 시 회전(rotate)하고, 이미 회전된 과거 토큰이 재사용되면 세션 전체를 무효화한다.
 * 키에 역할(ROLE_USER/ROLE_ADMIN)을 포함해 user와 admin의 id가 같아도 세션이 충돌하지 않는다.
 */
@Slf4j
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final long NOT_FOUND = 0L;
    private static final long REUSE_DETECTED = -1L;

    /**
     * 조회·비교·교체를 Redis 안에서 한 번에 실행해 동시 재발급 경쟁을 차단한다(원자 CAS).
     * ARGV는 저장 시와 같은 값 직렬화기로 넘어가므로 저장값과의 비교가 일치한다.
     * 반환: 1=회전 성공, 0=토큰 없음, -1=불일치(재사용 — 키 삭제까지 스크립트가 수행).
     */
    private static final RedisScript<Long> ROTATE_SCRIPT = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            end
            if current ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return -1
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtProperties properties;

    public RefreshTokenStore(RedisTemplate<String, Object> redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 로그인 시 현재 리프레시 토큰을 저장한다(TTL = refresh 만료시간). */
    public void save(String role, Long subjectId, String refreshToken) {
        redisTemplate.opsForValue().set(key(role, subjectId), refreshToken, properties.refreshTokenTtl());
    }

    /**
     * 재발급 회전. 제시된 토큰이 현재 토큰과 일치할 때만 새 토큰으로 교체한다.
     * 불일치(이미 회전된 토큰 재사용)면 세션을 무효화하고 TOKEN_REUSE_DETECTED를 던진다.
     */
    public void rotate(String role, Long subjectId, String presentedToken, String newToken) {
        Long result = redisTemplate.execute(ROTATE_SCRIPT, List.of(key(role, subjectId)),
                presentedToken, newToken, properties.refreshTokenTtl().toMillis());
        if (result == null || result == NOT_FOUND) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        if (result == REUSE_DETECTED) {
            // 탈취 가능성이 있는 보안 이벤트 — 토큰 값은 로그에 남기지 않는다.
            log.warn("리프레시 토큰 재사용 감지, 세션 무효화. role={}, subjectId={}", role, subjectId);
            throw new BusinessException(AuthErrorCode.TOKEN_REUSE_DETECTED);
        }
    }

    /** 세션 무효화(로그아웃·탈퇴·재사용 감지 시). 리프레시 토큰을 삭제한다. */
    public void revoke(String role, Long subjectId) {
        redisTemplate.delete(key(role, subjectId));
    }

    private String key(String role, Long subjectId) {
        return KEY_PREFIX + role + ":" + subjectId;
    }
}
