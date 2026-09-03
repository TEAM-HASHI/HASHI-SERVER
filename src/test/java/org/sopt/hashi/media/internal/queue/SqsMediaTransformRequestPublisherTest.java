package org.sopt.hashi.media.internal.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.media.domain.MediaPurpose;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@ExtendWith(MockitoExtension.class)
class SqsMediaTransformRequestPublisherTest {

    private static final String REQUEST_QUEUE_URL = "https://sqs.example.com/request";
    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";

    @Mock
    private SqsAsyncClient sqsAsyncClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void worker와_공유하는_golden_request를_그대로_전송한다() throws Exception {
        given(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .willReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
        SqsMediaTransformRequestPublisher publisher = publisher();

        publisher.publish(request());

        ArgumentCaptor<SendMessageRequest> captor =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsAsyncClient).sendMessage(captor.capture());
        SendMessageRequest sent = captor.getValue();
        JsonNode expected = objectMapper.readTree(Files.readString(Path.of(
                "worker",
                "image-transform",
                "test",
                "fixtures",
                "queue",
                "transform-request-v1.json"
        )));
        assertThat(sent.queueUrl()).isEqualTo(REQUEST_QUEUE_URL);
        assertThat(objectMapper.readTree(sent.messageBody())).isEqualTo(expected);
    }

    @Test
    void SQS_전송이_실패하면_listener가_성공으로_끝나지_않도록_예외를_전파한다() {
        CompletableFuture<SendMessageResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("temporary failure"));
        given(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).willReturn(failed);

        assertThatThrownBy(() -> publisher().publish(request()))
                .isInstanceOf(MediaQueuePublishException.class)
                .hasMessage("failed to publish media transform request");
    }

    private SqsMediaTransformRequestPublisher publisher() {
        MediaQueueProperties properties = new MediaQueueProperties(
                true,
                REQUEST_QUEUE_URL,
                "https://sqs.example.com/result",
                2,
                4,
                100,
                Duration.ofSeconds(20)
        );
        return new SqsMediaTransformRequestPublisher(
                sqsAsyncClient,
                objectMapper,
                properties
        );
    }

    private MediaTransformRequest request() {
        UUID assetId = UUID.fromString("a3af06f1-4ef2-46f8-a489-2347fb840447");
        return new MediaTransformRequest(
                1,
                UUID.fromString("ebb9b9d8-c427-564b-a70e-0fd4e1925e5a"),
                assetId,
                MediaPurpose.REVIEW,
                1,
                SPEC_DIGEST,
                "media/originals/%s/original".formatted(assetId),
                "version-1",
                "\"etag-value\"",
                "image/jpeg",
                1048576L
        );
    }
}
