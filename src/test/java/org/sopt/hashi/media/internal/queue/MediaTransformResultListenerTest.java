package org.sopt.hashi.media.internal.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.sopt.hashi.media.service.MediaTransformResultApplication;
import org.sopt.hashi.media.service.MediaTransformResultService;

class MediaTransformResultListenerTest {

    private final MediaTransformResultParser parser = mock(MediaTransformResultParser.class);
    private final MediaTransformResultService resultService =
            mock(MediaTransformResultService.class);
    private final MediaPipelineMetrics metrics = mock(MediaPipelineMetrics.class);
    private final Acknowledgement acknowledgement = mock(Acknowledgement.class);
    private final MediaTransformResultListener listener =
            new MediaTransformResultListener(parser, resultService, metrics);

    @Test
    void DB_반영이_끝난_뒤에만_메시지를_ACK한다() {
        MediaTransformResult result = failedResult();
        when(parser.parse("body")).thenReturn(result);
        MediaTransformResultApplication application = MediaTransformResultApplication.applied(
                Duration.ofSeconds(2));
        when(resultService.apply(result)).thenReturn(application);

        listener.consume("body", acknowledgement);

        InOrder order = inOrder(parser, resultService, metrics, acknowledgement);
        order.verify(parser).parse("body");
        order.verify(resultService).apply(result);
        order.verify(metrics).recordResult(result, application);
        order.verify(acknowledgement).acknowledge();
    }

    @Test
    void 계약_파싱이_실패하면_ACK하지_않는다() {
        when(parser.parse("body"))
                .thenThrow(new MediaTransformContractException("invalid contract"));

        assertThatThrownBy(() -> listener.consume("body", acknowledgement))
                .isInstanceOf(MediaTransformContractException.class);

        verify(metrics).recordResultContractError();
        verify(resultService, never()).apply(org.mockito.ArgumentMatchers.any());
        verify(acknowledgement, never()).acknowledge();
    }

    @Test
    void DB_반영이_실패하면_ACK하지_않는다() {
        MediaTransformResult result = failedResult();
        when(parser.parse("body")).thenReturn(result);
        when(resultService.apply(result)).thenThrow(new IllegalStateException("database failure"));

        assertThatThrownBy(() -> listener.consume("body", acknowledgement))
                .isInstanceOf(IllegalStateException.class);

        verify(metrics).recordResultInternalError(result);
        verify(acknowledgement, never()).acknowledge();
    }

    private MediaTransformFailedResult failedResult() {
        return new MediaTransformFailedResult(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f",
                "version-1",
                "\"etag-1\"",
                MediaTransformFailureCode.INVALID_IMAGE_DATA
        );
    }
}
