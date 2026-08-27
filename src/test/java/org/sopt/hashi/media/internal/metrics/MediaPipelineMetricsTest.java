package org.sopt.hashi.media.internal.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.internal.queue.MediaRenditionResult;
import org.sopt.hashi.media.internal.queue.MediaTransformSucceededResult;
import org.sopt.hashi.media.internal.queue.MediaVerifiedSource;
import org.sopt.hashi.media.service.MediaTransformResultApplication;

class MediaPipelineMetricsTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MediaPipelineMetrics metrics = new MediaPipelineMetrics(registry);

    @Test
    void 성공_결과의_처리시간과_role별_크기_절감률을_기록한다() {
        UUID assetId = UUID.randomUUID();
        MediaTransformSucceededResult result = new MediaTransformSucceededResult(
                1,
                UUID.randomUUID(),
                assetId,
                1,
                SPEC_DIGEST,
                "version-1",
                "\"etag-1\"",
                new MediaVerifiedSource(
                        "image/jpeg",
                        1_000L,
                        100,
                        100,
                        "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
                ),
                List.of(new MediaRenditionResult(
                        ImageRole.REVIEW_PREVIEW,
                        ImageFormat.WEBP,
                        100,
                        100,
                        200L,
                        "media/renditions/%s/v1/review-preview/100.webp".formatted(assetId)
                ))
        );

        metrics.recordResult(
                result,
                MediaTransformResultApplication.applied(Duration.ofSeconds(3))
        );

        assertThat(registry.get("hashi.media.transform.result")
                .tags("status", "succeeded", "outcome", "applied")
                .counter().count()).isEqualTo(1D);
        assertThat(registry.get("hashi.media.transform.processing.duration")
                .tag("status", "succeeded")
                .timer().totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3D);
        assertThat(registry.get("hashi.media.rendition.bytes")
                .tag("role", "review_preview")
                .summary().totalAmount()).isEqualTo(200D);
        assertThat(registry.get("hashi.media.rendition.source.ratio")
                .tag("role", "review_preview")
                .summary().totalAmount()).isEqualTo(0.2D);
    }

    @Test
    void request와_복구_EPR_관측값을_고정된_저카디널리티_tag로_기록한다() {
        metrics.recordRequest("published");
        metrics.recordRecovery("requested");
        metrics.recordEprResubmission("startup");

        assertThat(registry.get("hashi.media.transform.request")
                .tag("outcome", "published").counter().count()).isEqualTo(1D);
        assertThat(registry.get("hashi.media.recovery.request")
                .tag("outcome", "requested").counter().count()).isEqualTo(1D);
        assertThat(registry.get("hashi.media.epr.resubmit")
                .tag("trigger", "startup").counter().count()).isEqualTo(1D);
    }
}
