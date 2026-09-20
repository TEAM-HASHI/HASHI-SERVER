package org.sopt.hashi.media.internal.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.domain.MediaPipelineConfig;
import org.sopt.hashi.media.domain.MediaPipelineConfigRepository;
import org.sopt.hashi.media.domain.TargetProcessingStatus;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.spec.MediaSpecSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class MediaAssetMetricsRefresher {

    private static final EnumSet<ImageProcessingStatus> PENDING_CLEANUP_STATUSES =
            EnumSet.of(ImageProcessingStatus.PENDING_UPLOAD, ImageProcessingStatus.EXPIRED);

    private final ImageAssetRepository imageAssetRepository;
    private final MediaPipelineConfigRepository pipelineConfigRepository;
    private final MediaSpecRegistry mediaSpecRegistry;
    private final MediaRecoveryProperties properties;
    private final Clock clock;
    private final MultiGauge statusGauge;
    private final MultiGauge cleanupCandidateGauge;
    private final AtomicLong stalledCount = new AtomicLong();
    private final AtomicLong recoveryExhaustedCount = new AtomicLong();
    private final AtomicLong oldestProcessingAgeSeconds = new AtomicLong();
    private final AtomicLong issuanceAvailable = new AtomicLong();

    public MediaAssetMetricsRefresher(
            ImageAssetRepository imageAssetRepository,
            MediaPipelineConfigRepository pipelineConfigRepository,
            MediaSpecRegistry mediaSpecRegistry,
            MediaRecoveryProperties properties,
            MeterRegistry meterRegistry,
            @Qualifier("japanClock") Clock clock) {
        this.imageAssetRepository = imageAssetRepository;
        this.pipelineConfigRepository = pipelineConfigRepository;
        this.mediaSpecRegistry = mediaSpecRegistry;
        this.properties = properties;
        this.clock = clock;
        this.statusGauge = MultiGauge.builder("hashi.media.assets")
                .description("Current image asset count by processing status")
                .register(meterRegistry);
        this.cleanupCandidateGauge = MultiGauge.builder("hashi.media.cleanup.candidates")
                .description("Image asset cleanup candidate count by policy")
                .register(meterRegistry);
        Gauge.builder("hashi.media.processing.stalled", stalledCount, AtomicLong::doubleValue)
                .description("Image assets processing longer than the configured threshold")
                .register(meterRegistry);
        Gauge.builder(
                        "hashi.media.processing.recovery.exhausted",
                        recoveryExhaustedCount,
                        AtomicLong::doubleValue)
                .description("Image assets that exhausted automatic recovery attempts")
                .register(meterRegistry);
        Gauge.builder(
                        "hashi.media.processing.oldest.age.seconds",
                        oldestProcessingAgeSeconds,
                        AtomicLong::doubleValue)
                .description("Age in seconds of the oldest active processing target")
                .register(meterRegistry);
        Gauge.builder(
                        "hashi.media.issuance.available",
                        issuanceAvailable,
                        AtomicLong::doubleValue)
                .description("Whether media issuance is enabled with a matching packaged spec")
                .register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public void refreshOnStartup() {
        refresh();
    }

    @Scheduled(
            fixedDelayString = "${hashi.media.recovery.metrics-refresh-interval:30s}",
            initialDelayString = "${hashi.media.recovery.metrics-refresh-interval:30s}"
    )
    @Transactional(readOnly = true)
    public void refresh() {
        LocalDateTime now = LocalDateTime.now(clock);
        refreshStatusCounts();
        stalledCount.set(imageAssetRepository.countStalledProcessing(
                MediaCleanupStatus.ACTIVE,
                TargetProcessingStatus.PROCESSING,
                now.minus(properties.processingStaleAge())
        ));
        recoveryExhaustedCount.set(imageAssetRepository.countExhaustedProcessingRecovery(
                MediaCleanupStatus.ACTIVE,
                TargetProcessingStatus.PROCESSING,
                properties.processingMaxAttempts()
        ));
        oldestProcessingAgeSeconds.set(imageAssetRepository.findOldestProcessingStartedAt(
                        MediaCleanupStatus.ACTIVE,
                        TargetProcessingStatus.PROCESSING
                )
                .map(startedAt -> nonNegativeSeconds(startedAt, now))
                .orElse(0L));
        refreshCleanupCandidateCounts(now);
        issuanceAvailable.set(isIssuanceAvailable() ? 1L : 0L);
    }

    private void refreshStatusCounts() {
        Map<ImageProcessingStatus, Long> counts = new EnumMap<>(ImageProcessingStatus.class);
        imageAssetRepository.countByProcessingStatus().forEach(
                count -> counts.put(count.getStatus(), count.getAssetCount()));
        List<MultiGauge.Row<Number>> rows = Arrays.stream(ImageProcessingStatus.values())
                .map(status -> MultiGauge.Row.of(
                        Tags.of("status", status.name().toLowerCase(Locale.ROOT)),
                        counts.getOrDefault(status, 0L)
                ))
                .toList();
        statusGauge.register(rows, true);
    }

    private void refreshCleanupCandidateCounts(LocalDateTime now) {
        long pending = imageAssetRepository
                .countByCleanupStatusAndProcessingStatusInAndUpdatedAtBefore(
                        MediaCleanupStatus.ACTIVE,
                        PENDING_CLEANUP_STATUSES,
                        now.minus(properties.pendingRetention())
                );
        long directReady = imageAssetRepository
                .countByCleanupStatusAndBindingStatusAndCreationOriginAndProcessingStatusAndUpdatedAtBefore(
                        MediaCleanupStatus.ACTIVE,
                        ImageBindingStatus.UNBOUND,
                        MediaCreationOrigin.DIRECT_UPLOAD,
                        ImageProcessingStatus.READY,
                        now.minus(properties.directUnboundReadyRetention())
                );
        long backfillReady = imageAssetRepository
                .countByCleanupStatusAndBindingStatusAndCreationOriginAndProcessingStatusAndUpdatedAtBefore(
                        MediaCleanupStatus.ACTIVE,
                        ImageBindingStatus.UNBOUND,
                        MediaCreationOrigin.SYSTEM_BACKFILL,
                        ImageProcessingStatus.READY,
                        now.minus(properties.backfillUnboundRetention())
                );
        long failed = imageAssetRepository
                .countByCleanupStatusAndProcessingStatusAndUpdatedAtBefore(
                        MediaCleanupStatus.ACTIVE,
                        ImageProcessingStatus.FAILED,
                        now.minus(properties.failedRetention())
                );
        cleanupCandidateGauge.register(List.of(
                row("pending_or_expired", pending),
                row("direct_unbound_ready", directReady),
                row("backfill_unbound_ready", backfillReady),
                row("failed", failed)
        ), true);
    }

    private boolean isIssuanceAvailable() {
        return pipelineConfigRepository.findById(MediaPipelineConfig.SINGLETON_ID)
                .map(config -> mediaSpecRegistry.find(config.getCurrentSpecVersion())
                        .filter(spec -> matches(config, spec))
                        .isPresent())
                .orElse(false);
    }

    private boolean matches(MediaPipelineConfig config, MediaSpecSnapshot spec) {
        return config.isIssuanceEnabled()
                && config.matches(spec.version(), spec.digest());
    }

    private long nonNegativeSeconds(LocalDateTime startedAt, LocalDateTime now) {
        long seconds = Duration.between(startedAt, now).toSeconds();
        return Math.max(0L, seconds);
    }

    private MultiGauge.Row<Number> row(String type, long count) {
        return MultiGauge.Row.of(Tags.of("type", type), count);
    }
}
