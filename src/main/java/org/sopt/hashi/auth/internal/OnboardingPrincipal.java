package org.sopt.hashi.auth.internal;

/**
 * 온보딩(가입 전) 임시 인증 principal. 아직 회원이 아니므로 userId가 아니라 kakaoId를 담는다.
 * {@link MemberPrincipal}과 타입이 달라 온보딩 식별자를 회원 식별자로 오용할 수 없다.
 */
record OnboardingPrincipal(Long kakaoId) {
}
