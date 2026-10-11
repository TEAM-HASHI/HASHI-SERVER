package org.sopt.hashi.restaurant.internal.map;

import java.util.Base64;
import jakarta.annotation.PostConstruct;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 공개 활성화와 키 준비를 분리하며, 설정 오류가 앱 시작과 다른 API에 영향을 주지 않게 한다. */
@Slf4j
@Component
@ConfigurationProperties(prefix = "hashi.restaurant.map.session")
public class MapSessionProperties {
    private boolean enabled;
    private String signingKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    @PostConstruct
    void reportConfiguration() {
        if (!enabled) {
            log.info("Map query sessions are disabled.");
            return;
        }
        try {
            requireSigningKey();
        } catch (BusinessException exception) {
            log.warn("Map query sessions are enabled but the signing key is missing or invalid.");
        }
    }

    public void requireConfigured() {
        if (!enabled) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
        requireSigningKey();
    }

    public SecretKeySpec requireSigningKey() {
        try {
            if (signingKey == null || signingKey.length() > 128) {
                throw new IllegalArgumentException();
            }
            byte[] key = Base64.getDecoder().decode(signingKey);
            if (key.length < 32 || key.length > 64) {
                throw new IllegalArgumentException();
            }
            return new SecretKeySpec(key, "HmacSHA256");
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
    }
}
