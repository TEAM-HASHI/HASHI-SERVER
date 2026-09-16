package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaSourceIdentityTest {

    @Test
    void version_ID는_UTF8_1024_bytes까지_허용한다() {
        assertThat(MediaSourceIdentity.isValid("v".repeat(1024), "\"etag\""))
                .isTrue();
        assertThat(MediaSourceIdentity.isValid("가".repeat(341), "\"etag\""))
                .isTrue();
    }

    @Test
    void version_ID가_UTF8_1024_bytes를_넘으면_거부한다() {
        assertThat(MediaSourceIdentity.isValid("v".repeat(1025), "\"etag\""))
                .isFalse();
        assertThat(MediaSourceIdentity.isValid("가".repeat(342), "\"etag\""))
                .isFalse();
    }
}
