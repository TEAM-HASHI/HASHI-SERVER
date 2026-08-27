package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaProcessingRecoveryTransactionService {

    private final ImageAssetRepository imageAssetRepository;
    private final ApplicationEventPublisher eventPublisher;

    public MediaProcessingRecoveryTransactionService(
            ImageAssetRepository imageAssetRepository,
            ApplicationEventPublisher eventPublisher) {
        this.imageAssetRepository = imageAssetRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public boolean requestRetryIfStillStalled(
            MediaProcessingRecoveryCandidate candidate,
            LocalDateTime requestedAt,
            LocalDateTime staleBefore,
            LocalDateTime retryBefore,
            int maxAttempts
    ) {
        ImageAsset asset = imageAssetRepository.findByIdForUpdate(candidate.assetId())
                .orElse(null);
        if (asset == null || !asset.recordRecoveryRequest(
                candidate.jobId(), requestedAt, staleBefore, retryBefore, maxAttempts)) {
            return false;
        }
        eventPublisher.publishEvent(
                new MediaProcessingRequestedEvent(asset.getPublicId(), candidate.jobId()));
        return true;
    }
}
