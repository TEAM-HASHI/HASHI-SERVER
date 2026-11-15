package org.sopt.hashi.auth;

/**
 * auth 모듈의 공개 포트 — 소셜 계정과 회원(userId)의 연결을 다룬다.
 * 도메인 모듈(user)이 온보딩·탈퇴 트랜잭션 안에서 호출해, 회원 생성·삭제와 계정 연결·해제가 원자적으로 커밋되게 한다.
 */
public interface AuthAccountPort {

    /**
     * 현재 온보딩 인증 컨텍스트의 소셜 계정을 방금 생성된 회원(userId)과 연결한다.
     * 제공자·제공자 식별자는 auth가 온보딩 컨텍스트에서 직접 읽는다(user는 카카오 등 제공자를 알지 못한다).
     */
    void linkOnboardingAccount(Long userId);

    /**
     * 탈퇴한 회원의 인증을 정리한다 — 소셜 계정 연결(auth_account) 삭제, 리프레시 토큰 폐기, 액세스 토큰 블랙리스트 등록.
     * 탈퇴 트랜잭션 안에서만 호출한다. 계정 삭제는 트랜잭션에 참여하고 토큰 폐기·블랙리스트(Redis)는 커밋 전에 바로 반영되며,
     * 탈퇴가 롤백되면 블랙리스트는 되돌리고 회원은 다시 로그인하면 된다.
     */
    void unlinkWithdrawnAccount(Long userId);
}
