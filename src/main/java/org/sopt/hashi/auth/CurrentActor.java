package org.sopt.hashi.auth;

import java.util.Objects;

/**
 * 인증 주체의 유형과 auth 내부 식별자다. ONBOARDING subject는 외부 응답이나 로그에 노출하지 않는다.
 */
public record CurrentActor(ActorType type, Long subjectId) {

    public CurrentActor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
    }
}
