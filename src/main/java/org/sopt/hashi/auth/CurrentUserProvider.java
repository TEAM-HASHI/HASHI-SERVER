package org.sopt.hashi.auth;

/**
 * auth 모듈이 도메인에 공개하는 유일한 지점. 도메인은 현재 로그인 사용자가 필요하면 이 인터페이스로만 읽는다.
 * (도메인이 SecurityContextHolder나 auth.internal에 직접 접근하지 않는다 — auth.md §2)
 */
public interface CurrentUserProvider {

    /** 현재 로그인 사용자 id. 인증되지 않았으면 BusinessException(UNAUTHORIZED)을 던진다. */
    Long currentUserId();

    boolean isAuthenticated();
}
