package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.media.domain.MediaPurpose;

class MediaPurposeAccessPolicyTest {

    private final MediaPurposeAccessPolicy policy = new MediaPurposeAccessPolicy();

    @Test
    void PROFILE은_USER와_ONBOARDING만_허용한다() {
        assertThat(policy.isAllowed(ActorType.USER, MediaPurpose.PROFILE)).isTrue();
        assertThat(policy.isAllowed(ActorType.ONBOARDING, MediaPurpose.PROFILE)).isTrue();
        assertThat(policy.isAllowed(ActorType.ADMIN, MediaPurpose.PROFILE)).isFalse();
    }

    @Test
    void REVIEW는_USER만_허용한다() {
        assertThat(policy.isAllowed(ActorType.USER, MediaPurpose.REVIEW)).isTrue();
        assertThat(policy.isAllowed(ActorType.ADMIN, MediaPurpose.REVIEW)).isFalse();
        assertThat(policy.isAllowed(ActorType.ONBOARDING, MediaPurpose.REVIEW)).isFalse();
    }

    @Test
    void 운영_콘텐츠는_ADMIN만_허용한다() {
        assertThat(policy.isAllowed(ActorType.ADMIN, MediaPurpose.RESTAURANT)).isTrue();
        assertThat(policy.isAllowed(ActorType.ADMIN, MediaPurpose.RESTAURANT_MENU)).isTrue();
        assertThat(policy.isAllowed(ActorType.ADMIN, MediaPurpose.MAGAZINE_BANNER)).isTrue();
        assertThat(policy.isAllowed(ActorType.ADMIN, MediaPurpose.MAGAZINE_THUMBNAIL)).isTrue();
        assertThat(policy.isAllowed(ActorType.USER, MediaPurpose.RESTAURANT)).isFalse();
    }
}
