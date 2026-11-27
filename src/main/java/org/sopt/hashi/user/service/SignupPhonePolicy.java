package org.sopt.hashi.user.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class SignupPhonePolicy {
    final boolean enabled;
    private final SecretKeySpec key;

    SignupPhonePolicy(@Value("${hashi.signup-phone.enabled:false}") boolean enabled,
                      @Value("${hashi.signup-phone.hmac-key:}") String encodedKey,
                      @Value("${hashi.signup-phone.api-key:}") String apiKey) {
        this.enabled = enabled;
        if (!enabled) {
            key = null;
            return;
        }
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (decoded.length < 32 || apiKey.isBlank()) {
            throw new IllegalStateException("휴대폰 인증 활성화에는 전용 HMAC 키와 OCTOMO API 키가 필요합니다");
        }
        key = new SecretKeySpec(decoded, "HmacSHA256");
    }

    byte[] digest(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("휴대폰 인증 식별값을 계산하지 못했습니다", e);
        }
    }
}
