package org.sopt.hashi.auth.internal.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 세션 회전·앱 그룹 폐기·코드 교환을 Redis 안에서 원자적으로 처리한다. */
@Component
public class SessionTokenStore {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("auth/session.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final RedisTemplate<String, Object> legacyRedis;
    private final JwtProperties properties;
    private final ObjectMapper mapper;

    public SessionTokenStore(StringRedisTemplate redis, RedisTemplate<String, Object> legacyRedis,
                             JwtProperties properties, ObjectMapper mapper) {
        this.redis = redis;
        this.legacyRedis = legacyRedis;
        this.properties = properties;
        this.mapper = mapper;
    }

    public void save(Long userId, String sid, String client, String refresh) {
        execute(userId, "unused", "save", sid, client, digest(refresh), "");
    }

    public void rotate(Long userId, String sid, String client, String presented, String replacement) {
        execute(userId, "unused", "rotate", sid, client, digest(presented), digest(replacement));
    }

    public void migrate(Long userId, String sid, String presented, String replacement) {
        @SuppressWarnings("unchecked")
        var serializer = (org.springframework.data.redis.serializer.RedisSerializer<Object>) legacyRedis.getValueSerializer();
        String serialized = new String(serializer.serialize(presented), StandardCharsets.UTF_8);
        execute(userId, "auth:refresh:ROLE_USER:" + userId, "migrate", sid, "web", "",
                digest(replacement), serialized);
    }

    public void revoke(Long userId, String sid, String client, String presented) {
        execute(userId, "unused", "revoke", sid, client, digest(presented), "");
    }

    public void revokeAll(Long userId) {
        execute(userId, "unused", "revokeAll", "", "", "", "");
    }

    public void issueCode(Long userId, String parent, String code, String challenge) {
        String hash = digest(code);
        try {
            String value = mapper.writeValueAsString(new BridgeCode(userId.toString(), parent, challenge));
            execute(userId, codeKey(hash), "issue", parent, "app", "", "", hash, value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("인증 코드 직렬화 실패");
        }
    }

    public BridgeCode findCode(String code) {
        String value = redis.opsForValue().get(codeKey(digest(code)));
        if (value == null) {
            throw new BusinessException(AuthErrorCode.INVALID_WEBVIEW_CODE);
        }
        try {
            return mapper.readValue(value, BridgeCode.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(AuthErrorCode.INVALID_WEBVIEW_CODE);
        }
    }

    public void exchange(BridgeCode bridge, String code, String verifier, String sid, String refresh) {
        String hash = digest(code);
        try {
            execute(Long.valueOf(bridge.userId()), codeKey(hash), "exchange", bridge.parent(), "app", "",
                    digest(refresh), hash, digest(verifier), bridge.userId(), sid);
        } catch (BusinessException e) {
            throw new BusinessException(AuthErrorCode.INVALID_WEBVIEW_CODE);
        }
    }

    private void execute(Long userId, String thirdKey, String operation, String sid, String client,
                         String token, String replacement, String... extra) {
        var arguments = new java.util.ArrayList<String>(List.of(operation, sid, client, token, replacement,
                Long.toString(properties.refreshTokenTtl().toSeconds())));
        arguments.addAll(List.of(extra));
        Long result = redis.execute(SCRIPT,
                List.of("auth:sessions:ROLE_USER:" + userId, "auth:blacklist:user:" + userId, thirdKey),
                arguments.toArray());
        if (result != null && result == 1) {
            return;
        }
        if (result != null && result == -1) {
            throw new BusinessException(AuthErrorCode.TOKEN_REUSE_DETECTED);
        }
        if (result != null && result == -2) {
            throw new BusinessException(AuthErrorCode.SESSION_LIMIT_EXCEEDED);
        }
        throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    public static String digest(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String codeKey(String hash) { return "auth:webview:code:" + hash; }

    public record BridgeCode(String userId, String parent, String challenge) { }
}
