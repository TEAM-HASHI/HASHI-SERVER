package org.sopt.hashi.auth.internal.account;

/**
 * 소셜 로그인 제공자. auth_account.provider에 문자열(name)로 저장된다.
 * 제공자가 늘어나면 여기에 추가한다(카카오 외 확장을 위해 인증 계정을 users에서 분리했다).
 */
public enum AuthProvider {
    KAKAO
}
