package org.sopt.hashi.auth.dev;

import org.sopt.hashi.auth.internal.jwt.AuthRoles;

/** 개발용 토큰 발급 대상 역할 — 권한 문자열은 정식 발급 경로와 같은 {@link AuthRoles}에서 가져와 어긋나지 않는다. */
public enum DevTokenRole {

    USER(AuthRoles.USER),
    ADMIN(AuthRoles.ADMIN),
    ONBOARDING(AuthRoles.ONBOARDING);

    private final String authority;

    DevTokenRole(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }
}
