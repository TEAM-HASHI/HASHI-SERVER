package org.sopt.hashi.media.internal.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageRole;

class MediaTransformResultParserTest {

    private final MediaTransformResultParser parser =
            new MediaTransformResultParser(new ObjectMapper());

    @Test
    void worker의_SUCCEEDED_golden_fixture를_같은_계약으로_읽는다() throws IOException {
        MediaTransformResult result = parser.parse(fixture("transform-succeeded-v1.json"));

        assertThat(result).isInstanceOf(MediaTransformSucceededResult.class);
        MediaTransformSucceededResult succeeded = (MediaTransformSucceededResult) result;
        assertThat(succeeded.contractVersion()).isEqualTo(1);
        assertThat(succeeded.assetId().toString())
                .isEqualTo("a3af06f1-4ef2-46f8-a489-2347fb840447");
        assertThat(succeeded.verifiedSource().checksumSha256())
                .isEqualTo("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
        assertThat(succeeded.renditions())
                .singleElement()
                .extracting(MediaRenditionResult::role)
                .isEqualTo(ImageRole.REVIEW_PREVIEW);
    }

    @Test
    void worker의_FAILED_golden_fixture를_같은_계약으로_읽는다() throws IOException {
        MediaTransformResult result = parser.parse(fixture("transform-failed-v1.json"));

        assertThat(result).isInstanceOf(MediaTransformFailedResult.class);
        assertThat(((MediaTransformFailedResult) result).failureCode())
                .isEqualTo(MediaTransformFailureCode.INVALID_IMAGE_DATA);
    }

    @Test
    void 알_수_없는_필드가_추가된_결과는_거부한다() throws IOException {
        String body = fixture("transform-failed-v1.json")
                .replace("\"failureCode\"", "\"unexpected\":true,\"failureCode\"");

        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media failed result fields do not match contract v1");
    }

    @Test
    void 중복된_JSON_필드는_거부하고_원문을_예외에_노출하지_않는다() throws IOException {
        String secretValue = "secret-value-that-must-not-be-logged";
        String body = fixture("transform-failed-v1.json")
                .replace("\"status\": \"FAILED\"",
                        "\"status\": \"FAILED\", \"status\": \"%s\"".formatted(secretValue));

        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result is not valid JSON")
                .hasMessageNotContaining(secretValue)
                .hasNoCause();
    }

    @Test
    void 뒤에_다른_JSON이_붙은_메시지는_거부한다() throws IOException {
        String body = fixture("transform-failed-v1.json") + " {}";

        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result contains trailing JSON content");
    }

    @Test
    void 허용하지_않은_failureCode는_거부한다() throws IOException {
        String body = fixture("transform-failed-v1.json")
                .replace("INVALID_IMAGE_DATA", "UNKNOWN_FAILURE");

        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result failureCode is invalid");
    }

    @Test
    void source_픽셀_한도를_넘는_결과는_거부한다() throws IOException {
        String body = fixture("transform-succeeded-v1.json")
                .replace("\"width\": 3024", "\"width\": 10000")
                .replace("\"height\": 4032", "\"height\": 10000");

        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result verifiedSource pixel count is invalid");
    }

    private String fixture(String fileName) throws IOException {
        return Files.readString(Path.of(
                "worker", "image-transform", "test", "fixtures", "queue", fileName));
    }
}
