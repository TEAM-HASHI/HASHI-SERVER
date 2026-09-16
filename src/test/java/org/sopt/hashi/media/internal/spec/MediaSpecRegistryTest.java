package org.sopt.hashi.media.internal.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
