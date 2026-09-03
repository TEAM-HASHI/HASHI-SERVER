package org.sopt.hashi.media.internal.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.springframework.core.io.ClassPathResource;

class MediaSpecRegistryTest {

    @Test
    void v1_manifest의_정확한_LF_bytes_digest를_사용한다() {
        MediaSpecRegistry registry = new MediaSpecRegistry(new ObjectMapper());

        assertThat(registry.find(1)).contains(new MediaSpecSnapshot(
                1,
                "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f"
        ));
        assertThat(registry.find(2)).isEmpty();
    }

    @Test
    void source보다_크지_않은_purpose별_표준_rendition을_선택한다() {
        MediaSpecDefinition spec = new MediaSpecRegistry(new ObjectMapper())
                .findDefinition(1)
                .orElseThrow();

        assertThat(spec.expectedRenditions(MediaPurpose.REVIEW, 3024, 4032))
                .containsExactly(
                        expected(ImageRole.REVIEW_PREVIEW, 135, 135),
                        expected(ImageRole.REVIEW_PREVIEW, 270, 270),
                        expected(ImageRole.REVIEW_PREVIEW, 405, 405),
                        expected(ImageRole.REVIEW_DETAIL, 430, 628),
                        expected(ImageRole.REVIEW_DETAIL, 860, 1256),
                        expected(ImageRole.REVIEW_DETAIL, 1290, 1884)
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
                1,
                List.of(
                        new MediaRenditionDimensions(100, 50),
                        new MediaRenditionDimensions(100, 60)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("media role candidate widths must be unique");
    }

    private MediaExpectedRendition expected(ImageRole role, int width, int height) {
        return new MediaExpectedRendition(role, width, height);
    }

    @Test
    void 모든_candidate_높이는_aspect_ratio를_half_up으로_계산한다() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream inputStream = new ClassPathResource("media-specs/v1.json").getInputStream()) {
            JsonNode manifest = objectMapper.readTree(inputStream);
            assertThat(manifest.path("output").path("dimensionRounding").asText()).isEqualTo("half-up");

            for (Map.Entry<String, JsonNode> role : manifest.path("roles").properties()) {
                int ratioWidth = role.getValue().path("aspectRatio").path("width").asInt();
                int ratioHeight = role.getValue().path("aspectRatio").path("height").asInt();
                for (JsonNode candidate : role.getValue().path("candidates")) {
                    int width = candidate.path("width").asInt();
                    int expectedHeight = BigDecimal.valueOf(width)
                            .multiply(BigDecimal.valueOf(ratioHeight))
                            .divide(BigDecimal.valueOf(ratioWidth), 0, RoundingMode.HALF_UP)
                            .intValueExact();
                    assertThat(candidate.path("height").asInt())
                            .as("%s width=%d", role.getKey(), width)
                            .isEqualTo(expectedHeight);
                }
            }
        }
    }
}
