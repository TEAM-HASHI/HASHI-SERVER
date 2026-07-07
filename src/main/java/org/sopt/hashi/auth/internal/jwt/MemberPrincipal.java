package org.sopt.hashi.auth.internal.jwt;

/**
 * 정식 로그인 사용자의 인증 principal. 회원 식별자(userId)를 담는다.
 * 온보딩({@link OnboardingPrincipal})과 타입으로 구분되어 kakaoId를 userId로 오용할 수 없다.
 */
public record MemberPrincipal(Long userId) {
}
