package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MediaBackfillIdentityFactoryTest {

    private final MediaBackfillIdentityFactory factory = new MediaBackfillIdentityFactory();

    @Test
    void 동일_association과_source는_같은_SHA256을_사용한다() {
        LegacyImageSource source = source("photo.jpg", null, "\"etag-a\"");

        String identity = factory.create("RESTAURANT_IMAGE", 1L, "IMAGE", source);

        assertThat(identity).matches("[0-9a-f]{64}")
                .isEqualTo("6b3efce7b0f1741a96a3cf0e492b062b2632f44205d3d1b9a68ad6fa8dd27033")
                .isEqualTo(factory.create("RESTAURANT_IMAGE", 1L, "IMAGE", source));
    }

    @Test
    void 같은_파일도_association과_slot이_다르면_다른_asset을_사용한다() {
        LegacyImageSource source = source("photo.jpg", null, "\"etag-a\"");
        String first = factory.create("MAGAZINE", 1L, "BANNER", source);

        assertThat(first).isNotEqualTo(factory.create("MAGAZINE", 2L, "BANNER", source))
                .isNotEqualTo(factory.create("MAGAZINE", 1L, "THUMBNAIL", source))
                .isNotEqualTo(factory.create("PROFILE", 1L, "BANNER", source));
    }

    @Test
    void source의_key_version_또는_ETag가_바뀌면_새_identity를_사용한다() {
        String unversioned = factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", null, "a"));

        assertThat(unversioned)
                .isNotEqualTo(factory.create("PROFILE", 1L, "IMAGE", source("b.jpg", null, "a")))
                .isNotEqualTo(factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", null, "b")))
                .isNotEqualTo(factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", "a", "a")));
        assertThat(factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", "v1", "a")))
                .isNotEqualTo(factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", "v2", "a")));
    }

    @Test
    void 필드_구분자를_포함해도_다른_입력이_같은_직렬화가_되지_않는다() {
        LegacyImageSource source = source("한글\n사진.jpg", null, "etag");

        assertThat(factory.create("A\n1", 2L, "B", source))
                .isNotEqualTo(factory.create("A", 1L, "2\nB", source));
    }

    @Test
    void S3_null_version은_ETag_기반으로_정규화한다() {
        assertThat(factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", "null", "etag")))
                .isEqualTo(factory.create("PROFILE", 1L, "IMAGE", source("a.jpg", null, "etag")));
    }

    @Test
    void 잘못된_association은_hash를_발급하지_않는다() {
        LegacyImageSource source = source("a.jpg", null, "etag");

        assertThatThrownBy(() -> factory.create("", 1L, "IMAGE", source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create("PROFILE", 0L, "IMAGE", source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create("PROFILE", 1L, " ", source))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LegacyImageSource source(String key, String version, String eTag) {
        return new LegacyImageSource("test-delivery", key, version, eTag, "image/jpeg", 1024);
    }
}
