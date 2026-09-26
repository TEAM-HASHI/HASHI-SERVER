package org.sopt.hashi.media.internal.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void v1_manifest의_정확한_LF_bytes_digest를_사용한다() {
        MediaSpecRegistry registry = new MediaSpecRegistry(objectMapper);

        assertThat(registry.find(1)).contains(new MediaSpecSnapshot(
                1,
                "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f"
        ));
        assertThat(registry.find(2)).contains(new MediaSpecSnapshot(
                2,
                "b8e67084bcdf81ac7fc94951c905726a97ade3783320c67e03c505f5643ddf75"
        ));
    }

    @Test
    void source보다_크지_않은_purpose별_표준_rendition을_선택한다() {
        MediaSpecDefinition spec = new MediaSpecRegistry(objectMapper)
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
                        expected(ImageRole.REVIEW_DETAIL, 1290, 1884)
                );
    }

    @Test
    void 표준_후보보다_작은_source는_worker와_같은_half_up_fallback을_선택한다() {
        MediaSpecDefinition spec = new MediaSpecRegistry(objectMapper)
                .findDefinition(1)
                .orElseThrow();

        assertThat(spec.expectedRenditions(MediaPurpose.REVIEW, 100, 100))
                .containsExactly(
                        expected(ImageRole.REVIEW_PREVIEW, 100, 100),
                        expected(ImageRole.REVIEW_DETAIL, 68, 99)
                );
    }

    @Test
    void 카드뉴스는_원본비율을_유지하고_3대4_후보를_선택한다() {
        MediaSpecDefinition spec = new MediaSpecRegistry(objectMapper)
                .findDefinition(2)
                .orElseThrow();

        assertThat(spec.expectedRenditions(MediaPurpose.MAGAZINE_CARD_NEWS, 2160, 2880))
                .containsExactly(
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 432, 576),
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 864, 1152),
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 1296, 1728)
                );
        assertThat(spec.expectedRenditions(MediaPurpose.MAGAZINE_CARD_NEWS, 100, 100))
                .containsExactly(expected(ImageRole.MAGAZINE_CARD_NEWS, 100, 100));
        assertThat(spec.expectedRenditions(MediaPurpose.MAGAZINE_CARD_NEWS, 800, 1200))
                .containsExactly(
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 384, 576),
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 768, 1152),
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 800, 1200)
                );
        assertThat(spec.expectedRenditions(MediaPurpose.MAGAZINE_CARD_NEWS, 300, 1000))
                .containsExactly(
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 173, 576),
                        expected(ImageRole.MAGAZINE_CARD_NEWS, 300, 1000)
                );
    }

    @Test
    void 카드뉴스_실제_규격은_worker와_공유하는_반례에서도_일치한다() throws IOException {
        MediaSpecDefinition spec = new MediaSpecRegistry(objectMapper)
                .findDefinition(2).orElseThrow();
        try (InputStream input = new ClassPathResource(
                "media-specs/fixtures/card-news-dimensions.json").getInputStream()) {
            for (JsonNode fixture : objectMapper.readTree(input)) {
                List<MediaRenditionDimensions> expected = objectMapper.convertValue(
                        fixture.get("outputs"),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, MediaRenditionDimensions.class));
                assertThat(spec.expectedRenditions(MediaPurpose.MAGAZINE_CARD_NEWS,
                        fixture.get("sourceWidth").asInt(), fixture.get("sourceHeight").asInt()))
                        .extracting(rendition -> new MediaRenditionDimensions(
                                rendition.width(), rendition.height()))
                        .containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void 카드뉴스의_기본_크기도_폭_중복을_제거한_최종_크기를_선택한다() {
        MediaRoleSpec role = new MediaSpecRegistry(objectMapper)
                .findDefinition(2).orElseThrow().roleSpecs().get(ImageRole.MAGAZINE_CARD_NEWS);
        assertThat(role.defaultOutputDimensions(1, 1200))
                .isEqualTo(new MediaRenditionDimensions(1, 1200));
        assertThat(role.defaultOutputDimensions(2, 1200))
                .isEqualTo(new MediaRenditionDimensions(2, 1200));
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

    @Test
    void 모든_candidate_높이는_aspect_ratio를_half_up으로_계산한다() throws IOException {
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

    @Test
    void worker가_지원하지_않는_processor_revision은_서버도_거부한다() throws IOException {
        ObjectNode manifest = manifest();
        manifest.put("processorRevision", "future-worker-v2");

        assertInvalid(manifest, "processorRevision");
    }

    @Test
    void worker와_다른_output_계약은_서버도_거부한다() throws IOException {
        ObjectNode manifest = manifest();
        ((ObjectNode) manifest.path("output")).put("metadata", "keep");

        assertInvalid(manifest, "metadata");
    }

    @Test
    void 서버_enum과_다른_purpose나_role_목록은_발급_전에_거부한다() throws IOException {
        ObjectNode missingPurpose = manifest();
        ((ObjectNode) missingPurpose.path("purposes")).remove("REVIEW");
        assertInvalid(missingPurpose, "purposes");

        ObjectNode unknownRole = manifest();
        ((ObjectNode) unknownRole.path("roles")).set(
                "FUTURE_ROLE", unknownRole.path("roles").path("PROFILE_AVATAR").deepCopy());
        assertInvalid(unknownRole, "roles");
    }

    @Test
    void role의_crop_품질_default와_candidate_계약을_모두_검증한다() throws IOException {
        ObjectNode unsupportedFit = manifest();
        ((ObjectNode) unsupportedFit.path("roles").path("PROFILE_AVATAR"))
                .put("fit", "contain");
        assertInvalid(unsupportedFit, "fit");

        ObjectNode invalidDefault = manifest();
        ((ObjectNode) invalidDefault.path("roles").path("PROFILE_AVATAR"))
                .put("defaultWidth", 100);
        assertInvalid(invalidDefault, "defaultWidth");

        ObjectNode invalidCandidate = manifest();
        ((ObjectNode) invalidCandidate.path("roles").path("PROFILE_AVATAR")
                .path("candidates").get(0)).put("height", 47);
        assertInvalid(invalidCandidate, "aspectRatio");
    }

    private ObjectNode manifest() throws IOException {
        try (InputStream inputStream = new ClassPathResource("media-specs/v1.json").getInputStream()) {
            return (ObjectNode) objectMapper.readTree(inputStream);
        }
    }

    private void assertInvalid(ObjectNode manifest, String messagePart) {
        assertThatThrownBy(() -> MediaSpecManifestValidator.validate(manifest, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }
}
