package org.sopt.hashi.auth.internal.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 쿠키로 자동 전송되는 크리덴셜(refresh·signup)의 CSRF 방어 — Origin 검증.
 * SameSite=None이라 브라우저가 교차 사이트에서도 쿠키를 실어 보내므로, 허용 목록 밖 Origin은 거부한다.
 * Origin 없음(동일 출처·비브라우저)은 허용한다(브라우저가 교차 출처 요청에만 Origin을 붙인다).
 */
@Component
public class OriginValidator {

    private final List<String> allowedOrigins;

    public OriginValidator(@Value("${hashi.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public boolean isAllowed(String origin) {
        return origin == null || allowedOrigins.contains(origin);
    }
}
