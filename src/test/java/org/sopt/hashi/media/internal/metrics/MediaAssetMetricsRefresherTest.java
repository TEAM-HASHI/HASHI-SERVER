package org.sopt.hashi.media.internal.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaPipelineConfig;
import org.sopt.hashi.media.domain.MediaPipelineConfigRepository;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.spec.MediaSpecSnapshot;

class MediaAssetMetricsRefresherTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T09:00:00Z"),
            ZoneId.of("Asia/Tokyo")
    );

    private final ImageAssetRepository imageAssetRepository =
            mock(ImageAssetRepository.class);
    private final MediaPipelineConfigRepository pipelineConfigRepository =
            mock(MediaPipelineConfigRepository.class);
    private final MediaSpecRegistry mediaSpecRegistry = mock(MediaSpecRegistry.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 처리_상태와_정체_cleanup_issuance를_DB_snapshot으로_갱신한다() {
        ImageAssetRepository.ProcessingStatusCount readyCount =
                mock(ImageAssetRepository.ProcessingStatusCount.class);
        given(readyCount.getStatus()).willReturn(ImageProcessingStatus.READY);
        given(readyCount.getAssetCount()).willReturn(7L);
        given(imageAssetRepository.countByProcessingStatus()).willReturn(List.of(readyCount));
        given(imageAssetRepository.countStalledProcessing(any(), any(), any())).willReturn(2L);
        given(imageAssetRepository.countExhaustedProcessingRecovery(any(), any(), anyInt()))
                .willReturn(1L);
        given(imageAssetRepository.findOldestProcessingStartedAt(any(), any()))
                .willReturn(Optional.of(LocalDateTime.now(CLOCK).minusMinutes(30)));
        given(imageAssetRepository
                .countByCleanupStatusAndProcessingStatusInAndUpdatedAtBefore(
                        any(), any(), any())).willReturn(3L);
        given(imageAssetRepository
                .countByCleanupStatusAndBindingStatusAndCreationOriginAndProcessingStatusAndUpdatedAtBefore(
                        any(), any(), any(), any(), any()))
                .willReturn(4L, 5L);
        given(imageAssetRepository
                .countByCleanupStatusAndProcessingStatusAndUpdatedAtBefore(
                        any(), any(), any())).willReturn(6L);
        MediaPipelineConfig config = mock(MediaPipelineConfig.class);
        given(config.getCurrentSpecVersion()).willReturn(1);
        given(config.isIssuanceEnabled()).willReturn(true);
        given(config.matches(1, SPEC_DIGEST)).willReturn(true);
        given(pipelineConfigRepository.findById(MediaPipelineConfig.SINGLETON_ID))
                .willReturn(Optional.of(config));
        given(mediaSpecRegistry.find(1))
                .willReturn(Optional.of(new MediaSpecSnapshot(1, SPEC_DIGEST)));
        MediaAssetMetricsRefresher refresher = new MediaAssetMetricsRefresher(
                imageAssetRepository,
                pipelineConfigRepository,
                mediaSpecRegistry,
                properties(),
                registry,
                CLOCK
        );

        refresher.refresh();

        assertThat(gauge("hashi.media.assets", "status", "ready")).isEqualTo(7D);
        assertThat(gauge("hashi.media.assets", "status", "failed")).isZero();
        assertThat(gauge("hashi.media.processing.stalled")).isEqualTo(2D);
        assertThat(gauge("hashi.media.processing.recovery.exhausted")).isEqualTo(1D);
        assertThat(gauge("hashi.media.processing.oldest.age.seconds")).isEqualTo(1800D);
        assertThat(gauge(
                "hashi.media.cleanup.candidates", "type", "pending_or_expired")).isEqualTo(3D);
        assertThat(gauge(
                "hashi.media.cleanup.candidates", "type", "direct_unbound_ready")).isEqualTo(4D);
        assertThat(gauge(
                "hashi.media.cleanup.candidates", "type", "backfill_unbound_ready")).isEqualTo(5D);
        assertThat(gauge("hashi.media.cleanup.candidates", "type", "failed")).isEqualTo(6D);
        assertThat(gauge("hashi.media.issuance.available")).isEqualTo(1D);
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private MediaRecoveryProperties properties() {
        return new MediaRecoveryProperties(
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                3,
                100,
                10,
                Duration.ofSeconds(30),
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofDays(7),
                Duration.ofDays(7)
        );
    }
}
