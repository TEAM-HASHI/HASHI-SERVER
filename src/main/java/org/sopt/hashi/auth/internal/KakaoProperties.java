package org.sopt.hashi.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 OAuth 설정. 값은 환경변수(KAKAO_CLIENT_ID 등)로 주입한다. clientSecret은 카카오 콘솔에서 켠 경우만 사용(선택).
 */
@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(String clientId, String redirectUri, String clientSecret) {
}
