package org.sopt.hashi.auth;

/**
 * USER, ADMIN, ONBOARDING을 구분해야 하는 제한된 기능에 현재 인증 주체를 제공한다.
 */
public interface CurrentActorProvider {

    /** 현재 인증 주체. 인증 정보가 없거나 principal과 role이 일치하지 않으면 UNAUTHORIZED다. */
    CurrentActor currentActor();
}
