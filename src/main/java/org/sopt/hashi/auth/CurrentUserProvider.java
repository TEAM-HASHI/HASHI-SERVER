package org.sopt.hashi.auth;

/**
 * auth 모듈이 공개하는 현재 로그인 사용자 조회 계약. 일반 사용자 도메인은 이 인터페이스로 현재 사용자를 읽는다.
 * (도메인이 SecurityContextHolder나 auth.internal에 직접 접근하지 않는다 — auth.md §2)
 */
public interface CurrentUserProvider {

    /** 현재 로그인 사용자 id. 인증되지 않았으면 BusinessException(UNAUTHORIZED)을 던진다. */
    Long currentUserId();

    /** 정식 로그인 회원(ROLE_USER)인지 — 익명·온보딩·어드민은 false. */
    boolean isAuthenticatedUser();
}
