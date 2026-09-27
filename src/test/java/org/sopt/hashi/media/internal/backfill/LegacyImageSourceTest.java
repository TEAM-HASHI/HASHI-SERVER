package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyImageSourceTest {

    @Test
    void legacy_content_type을_정규화하고_원시_경로는_toString에서_숨긴다() {
        LegacyImageSource source = new LegacyImageSource(
                "test-delivery", "private-name.jpg", null, "etag", " Image/JPEG ", 1024);

        assertThat(source.contentType()).isEqualTo("image/jpeg");
        assertThat(source.toString()).isEqualTo("LegacyImageSource[redacted]");
        BackfillOriginalCopy copy = new BackfillOriginalCopy(
                "media/originals/test/original", "version", "etag", "image/jpeg", 1024, "a".repeat(64));
        assertThat(copy.toString()).isEqualTo("BackfillOriginalCopy[redacted]");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 5242881L})
    void 최대_5MiB_정지_이미지_크기_계약을_강제한다(long bytes) {
        assertThatThrownBy(() -> new LegacyImageSource(
                "test-delivery", "photo.jpg", null, "etag", "image/jpeg", bytes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/svg+xml", "application/octet-stream", ""})
    void 지원하지_않는_MIME은_변환을_시작하지_않는다(String type) {
        assertThatThrownBy(() -> new LegacyImageSource(
                "test-delivery", "photo.jpg", null, "etag", type, 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 새_media_경로를_legacy_source로_재수집하지_않는다() {
        assertThatThrownBy(() -> new LegacyImageSource(
                "test-delivery", "media/renditions/a.webp", null, "etag", "image/webp", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
