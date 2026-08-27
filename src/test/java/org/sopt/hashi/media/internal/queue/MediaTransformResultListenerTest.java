package org.sopt.hashi.media.internal.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.sopt.hashi.media.service.MediaTransformResultDisposition;
import org.sopt.hashi.media.service.MediaTransformResultService;

class MediaTransformResultListenerTest {

    private final MediaTransformResultParser parser = mock(MediaTransformResultParser.class);
    private final MediaTransformResultService resultService =
            mock(MediaTransformResultService.class);
    private final Acknowledgement acknowledgement = mock(Acknowledgement.class);
    private final MediaTransformResultListener listener =
            new MediaTransformResultListener(parser, resultService);

    @Test
    void DB_반영이_끝난_뒤에만_메시지를_ACK한다() {
        MediaTransformResult result = failedResult();
        when(parser.parse("body")).thenReturn(result);
        when(resultService.apply(result)).thenReturn(MediaTransformResultDisposition.APPLIED);

        listener.consume("body", acknowledgement);

        InOrder order = inOrder(parser, resultService, acknowledgement);
        order.verify(parser).parse("body");
        order.verify(resultService).apply(result);
        order.verify(acknowledgement).acknowledge();
    }

    @Test
    void 계약_파싱이_실패하면_ACK하지_않는다() {
        when(parser.parse("body"))
                .thenThrow(new MediaTransformContractException("invalid contract"));

        assertThatThrownBy(() -> listener.consume("body", acknowledgement))
                .isInstanceOf(MediaTransformContractException.class);

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

        verify(acknowledgement, never()).acknowledge();
    }

    private MediaTransformFailedResult failedResult() {
        return new MediaTransformFailedResult(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32",
                "version-1",
                "\"etag-1\"",
                MediaTransformFailureCode.INVALID_IMAGE_DATA
        );
    }
}
