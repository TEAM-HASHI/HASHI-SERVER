package org.sopt.hashi.auth.internal;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 카카오 OAuth 설정. 값은 환경변수(KAKAO_CLIENT_ID 등)로 주입한다. clientSecret은 카카오 콘솔에서 켠 경우만 사용(선택).
 * 필수값(clientId·redirectUri) 누락은 부팅 시점에 즉시 실패시킨다(로그인 때 터지지 않도록).
 */
@Validated
@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(
        @NotBlank String clientId,
        @NotBlank String redirectUri,
        String clientSecret) {
}
