package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;

class RestaurantMediaBackfillDryRunFailureTest {

    private static final String KEY = "fixture-private-source-never-log.jpg";
    private static final String PAYLOAD = "fixture-private-database-payload";
    private static final RestaurantMediaBackfillTarget TARGET = RestaurantMediaBackfillTarget.RESTAURANT_IMAGE;

    private final RestaurantMediaBackfillCandidateReader reader = mock(RestaurantMediaBackfillCandidateReader.class);
    private final RestaurantMediaBackfillCheckpointStore checkpoint = mock(RestaurantMediaBackfillCheckpointStore.class);
    private final RestaurantMediaBackfillAttachmentService attachment = mock(RestaurantMediaBackfillAttachmentService.class);
    private final MediaBackfillPort port = mock(MediaBackfillPort.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void 지표_레지스트리를_종료한다() {
        registry.close();
    }

    @Test
    void 다음_batch_조회_실패에도_이미_관측한_source_실패를_부분_결과에_보존한다() {
        stubMissingFirstSource();
        given(reader.findBatch(TARGET, 1L, 2L, 1)).willThrow(new IllegalStateException(PAYLOAD));

        RestaurantMediaBackfillSummary summary = runner().execute();

        assertPartialFailure(summary);
        verifyNoInteractions(checkpoint, attachment);
        verify(port, never()).prepare(any(), any());
        verify(port, never()).claimReady(any());
    }

    @Test
    void 종료_interrupt에도_부분_결과와_interrupt_flag를_보존한다() {
        stubMissingFirstSource();
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new MediaBackfillSourceException(Reason.SOURCE_MISSING);
        }).when(port).inspect(any());
        try {
            RestaurantMediaBackfillSummary summary = runner().execute();

            assertPartialFailure(summary);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(reader, never()).findBatch(TARGET, 1L, 2L, 1);
            verifyNoInteractions(checkpoint, attachment);
        } finally {
            // 테스트 스레드의 interrupt를 다음 테스트로 전달하지 않는다.
            Thread.interrupted();
        }
    }

    @Test
    void 최초_조회가_실패하면_실패한_실행과_비어_있는_관측을_보고한다() {
        given(reader.findUpperBound(TARGET)).willThrow(new IllegalStateException(PAYLOAD));

        RestaurantMediaBackfillSummary summary = runner().execute();

        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.FAILED);
        assertThat(summary.scannedCount()).isZero();
        assertThat(summary.sourceFailuresThisExecution()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
        verifyNoInteractions(port, checkpoint, attachment);
    }

    @Test
    void 비정상_종료_로그에도_부분_원인을_남기되_원시_key와_예외_payload는_노출하지_않는다() {
        stubMissingFirstSource();
        given(reader.findBatch(TARGET, 1L, 2L, 1)).willThrow(new IllegalStateException(PAYLOAD));
        Logger logger = (Logger) LoggerFactory.getLogger(RestaurantMediaBackfillRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner().runOnStartup();

            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("sourceFailuresThisExecution={SOURCE_MISSING=1}")
                        .doesNotContain(KEY, PAYLOAD);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list.getLast().getFormattedMessage()).contains("status=FAILED", "scanned=1");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void stubMissingFirstSource() {
        given(reader.findUpperBound(TARGET)).willReturn(2L);
        given(reader.findBatch(TARGET, 0L, 2L, 1))
                .willReturn(List.of(new RestaurantMediaBackfillCandidate(TARGET, 1L, 100L, KEY)));
        given(port.inspect(any())).willThrow(new MediaBackfillSourceException(Reason.SOURCE_MISSING));
    }

    private RestaurantMediaBackfillRunner runner() {
        RestaurantMediaBackfillProperties properties = new RestaurantMediaBackfillProperties(
                true, "", TARGET, RestaurantMediaBackfillMode.DRY_RUN, 1, 3, Duration.ofMinutes(5), 3, Duration.ZERO);
        return new RestaurantMediaBackfillRunner(properties, reader, checkpoint, attachment, port, registry);
    }

    private void assertPartialFailure(RestaurantMediaBackfillSummary summary) {
        assertThat(summary.status()).isEqualTo(RestaurantMediaBackfillSummary.Status.FAILED);
        assertThat(summary.scannedCount()).isEqualTo(1L);
        assertThat(summary.failedCount()).isEqualTo(1L);
        assertThat(summary.sourceFailuresThisExecution()).containsExactlyEntriesOf(Map.of(Reason.SOURCE_MISSING, 1L));
        assertThat(registry.get(RestaurantMediaBackfillSourceFailures.METRIC_NAME)
                .tag("reason", Reason.SOURCE_MISSING.name()).counter().count()).isEqualTo(1);
    }
}
