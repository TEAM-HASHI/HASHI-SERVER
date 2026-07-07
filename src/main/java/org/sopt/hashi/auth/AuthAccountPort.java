package org.sopt.hashi.auth;

/**
 * auth 모듈의 공개 포트 — 소셜 계정과 회원(userId)의 연결을 다룬다.
 * 도메인 모듈(user)이 온보딩 트랜잭션 안에서 호출해, 회원 생성과 계정 연결이 원자적으로 커밋되게 한다.
 */
public interface AuthAccountPort {

    /**
     * 현재 온보딩 인증 컨텍스트의 소셜 계정을 방금 생성된 회원(userId)과 연결한다.
     * 제공자·제공자 식별자는 auth가 온보딩 컨텍스트에서 직접 읽는다(user는 카카오 등 제공자를 알지 못한다).
     */
    void linkOnboardingAccount(Long userId);
}
