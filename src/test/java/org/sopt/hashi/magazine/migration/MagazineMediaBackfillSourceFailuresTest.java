package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;

class MagazineMediaBackfillSourceFailuresTest {

    @Test
    void 지표_label은_고정_enum_조합만_사용하고_실행별_집계는_불변_snapshot으로_반환한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            for (MagazineMediaBackfillTarget target : MagazineMediaBackfillTarget.values()) {
                for (MagazineMediaBackfillMode mode : MagazineMediaBackfillMode.values()) {
                    MagazineMediaBackfillSourceFailures failures = new MagazineMediaBackfillSourceFailures(
                            target, mode, registry);
                    for (Reason reason : Reason.values()) {
                        failures.record(reason);
                    }
                    Map<Reason, Long> snapshot = failures.snapshot();
                    assertThat(snapshot).hasSize(Reason.values().length).containsEntry(Reason.SOURCE_MISSING, 1L);
                    assertThatThrownBy(() -> snapshot.put(Reason.SOURCE_MISSING, 9L))
                            .isInstanceOf(UnsupportedOperationException.class);
                    failures.record(Reason.SOURCE_MISSING);
                    assertThat(snapshot).containsEntry(Reason.SOURCE_MISSING, 1L);
                    assertThat(failures.snapshot()).containsEntry(Reason.SOURCE_MISSING, 2L);
                }
            }
            int combinations = MagazineMediaBackfillTarget.values().length
                    * MagazineMediaBackfillMode.values().length * Reason.values().length;
            assertThat(registry.getMeters()).hasSize(combinations).allSatisfy(meter -> {
                assertThat(meter.getId().getName()).isEqualTo(MagazineMediaBackfillSourceFailures.METRIC_NAME);
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("target", "mode", "reason");
                assertThat(MagazineMediaBackfillTarget.valueOf(meter.getId().getTag("target"))).isNotNull();
                assertThat(MagazineMediaBackfillMode.valueOf(meter.getId().getTag("mode"))).isNotNull();
                assertThat(Reason.valueOf(meter.getId().getTag("reason"))).isNotNull();
            });
        } finally {
            registry.close();
        }
    }

    @Test
    void 지표_장애가_원인_집계를_잃거나_후속_처리를_중단하거나_예외_payload를_노출하지_않는다() {
        MeterRegistry registry = mock(MeterRegistry.class);
        String privatePayload = "fixture-metric-payload-do-not-log";
        given(registry.counter(eq(MagazineMediaBackfillSourceFailures.METRIC_NAME), any(String[].class)))
                .willThrow(new IllegalStateException(privatePayload));
        MagazineMediaBackfillSourceFailures failures = new MagazineMediaBackfillSourceFailures(
                MagazineMediaBackfillTarget.MAGAZINE_BANNER, MagazineMediaBackfillMode.PREPARE, registry);
        Logger logger = (Logger) LoggerFactory.getLogger(MagazineMediaBackfillSourceFailures.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            failures.record(Reason.SOURCE_UNREADABLE);

            assertThat(failures.snapshot()).containsEntry(Reason.SOURCE_UNREADABLE, 1L);
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("reason=SOURCE_UNREADABLE",
                        "errorType=IllegalStateException").doesNotContain(privatePayload);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
