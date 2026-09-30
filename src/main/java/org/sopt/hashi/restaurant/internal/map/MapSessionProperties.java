package org.sopt.hashi.restaurant.internal.map;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 요청 시 검증해 지도 전용 비밀값 누락이 앱 시작과 다른 API에 영향을 주지 않게 한다. */
@Component
@ConfigurationProperties(prefix = "hashi.restaurant.map.session")
public class MapSessionProperties {
    private String signingKey;

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
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
