package org.sopt.hashi.auth.internal.kakao;

/**
 * 카카오 로그인 응답. 토큰은 바디로 내리지 않는다 —
 * 회원이면 access는 Authorization 헤더·refresh는 쿠키로, 비회원이면 온보딩 임시 토큰을 signup_token 쿠키로 내린다.
 * 바디는 클라가 다음 화면(홈 vs 가입)을 정하도록 registered 플래그만 담는다.
 */
public record KakaoLoginResponse(boolean registered) {

    public static KakaoLoginResponse member() {
        return new KakaoLoginResponse(true);
    }

    public static KakaoLoginResponse onboardingRequired() {
        return new KakaoLoginResponse(false);
    }
}
