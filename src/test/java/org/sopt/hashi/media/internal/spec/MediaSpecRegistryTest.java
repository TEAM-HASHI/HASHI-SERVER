package org.sopt.hashi.media.internal.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;

class MediaSpecRegistryTest {

    @Test
    void v1_manifest의_정확한_LF_bytes_digest를_사용한다() {
        MediaSpecRegistry registry = new MediaSpecRegistry(new ObjectMapper());

        assertThat(registry.find(1)).contains(new MediaSpecSnapshot(
                1,
                "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32"
        ));
        assertThat(registry.find(2)).isEmpty();
    }

    @Test
    void source보다_크지_않은_purpose별_표준_rendition을_선택한다() {
        MediaSpecDefinition spec = new MediaSpecRegistry(new ObjectMapper())
                .findDefinition(1)
                .orElseThrow();

        assertThat(spec.roleSpecs().get(ImageRole.REVIEW_PREVIEW).defaultWidth()).isEqualTo(270);
        assertThat(spec.expectedRenditions(MediaPurpose.REVIEW, 3024, 4032))
                .containsExactly(
                        expected(ImageRole.REVIEW_PREVIEW, 135, 135),
                        expected(ImageRole.REVIEW_PREVIEW, 270, 270),
                        expected(ImageRole.REVIEW_PREVIEW, 405, 405),
                        expected(ImageRole.REVIEW_DETAIL, 430, 628),
                        expected(ImageRole.REVIEW_DETAIL, 860, 1256),
                        expected(ImageRole.REVIEW_DETAIL, 1290, 1885)
                );
    }

    @Test
    void 표준_후보보다_작은_source는_worker와_같은_half_up_fallback을_선택한다() {
        MediaSpecDefinition spec = new MediaSpecRegistry(new ObjectMapper())
                .findDefinition(1)
                .orElseThrow();

        assertThat(spec.expectedRenditions(MediaPurpose.REVIEW, 100, 100))
                .containsExactly(
                        expected(ImageRole.REVIEW_PREVIEW, 100, 100),
                        expected(ImageRole.REVIEW_DETAIL, 68, 99)
                );
    }

    @Test
    void 같은_role의_candidate_width는_중복될_수_없다() {
        assertThatThrownBy(() -> new MediaRoleSpec(
                1,
                1,
                100,
                1,
                List.of(
                        new MediaRenditionDimensions(100, 50),
                        new MediaRenditionDimensions(100, 60)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("media role candidate widths must be unique");
    }

    @Test
    void default_width는_candidate에_포함되어야_한다() {
        assertThatThrownBy(() -> new MediaRoleSpec(
                1,
                1,
                200,
                1,
                List.of(new MediaRenditionDimensions(100, 100))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("media role default width must be a candidate");
    }

    private MediaExpectedRendition expected(ImageRole role, int width, int height) {
        return new MediaExpectedRendition(role, width, height);
    }
}
