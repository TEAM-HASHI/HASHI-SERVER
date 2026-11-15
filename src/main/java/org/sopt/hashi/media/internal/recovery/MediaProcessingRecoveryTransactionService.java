package org.sopt.hashi.media.internal.recovery;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaProcessingRecoveryTransactionService {

    private final ImageAssetRepository imageAssetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public MediaProcessingRecoveryTransactionService(
            ImageAssetRepository imageAssetRepository,
            ApplicationEventPublisher eventPublisher,
            @Qualifier("japanClock") Clock clock) {
        this.imageAssetRepository = imageAssetRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public boolean requestRetryIfStillStalled(
            MediaProcessingRecoveryCandidate candidate,
            Duration staleAge,
            Duration retryInterval,
            int maxAttempts
    ) {
        ImageAsset asset = imageAssetRepository.findByIdForUpdate(candidate.assetId())
                .orElse(null);
        LocalDateTime requestedAt = LocalDateTime.now(clock);
        if (asset == null || !asset.recordRecoveryRequest(
                candidate.jobId(), requestedAt, requestedAt.minus(staleAge),
                requestedAt.minus(retryInterval), maxAttempts)) {
            return false;
        }
        eventPublisher.publishEvent(
                new MediaProcessingRequestedEvent(asset.getPublicId(), candidate.jobId()));
        return true;
    }
}
