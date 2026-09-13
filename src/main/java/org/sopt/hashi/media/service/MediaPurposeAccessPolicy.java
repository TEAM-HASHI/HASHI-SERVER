package org.sopt.hashi.media.service;

import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.springframework.stereotype.Component;

@Component
class MediaPurposeAccessPolicy {

    boolean isAllowed(ActorType actorType, MediaPurpose purpose) {
        return switch (purpose) {
            case PROFILE -> actorType == ActorType.USER || actorType == ActorType.ONBOARDING;
            case REVIEW -> actorType == ActorType.USER;
            case RESTAURANT, RESTAURANT_MENU, MAGAZINE_BANNER, MAGAZINE_THUMBNAIL ->
                    actorType == ActorType.ADMIN;
        };
    }
}
