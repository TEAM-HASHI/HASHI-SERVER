package org.sopt.hashi.auth.internal.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 카카오 콘솔의 숫자 앱 ID. 누락 시 기존 웹은 유지하고 앱 로그인만 거부한다. */
@ConfigurationProperties("hashi.auth.app")
public record KakaoAppProperties(Long kakaoAppId) { }
