package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.TargetProcessingStatus;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaProcessingRecoveryReader {

    private final ImageAssetRepository imageAssetRepository;

    public MediaProcessingRecoveryReader(ImageAssetRepository imageAssetRepository) {
        this.imageAssetRepository = imageAssetRepository;
    }

    @Transactional(readOnly = true)
    public List<MediaProcessingRecoveryCandidate> findBatch(
            LocalDateTime staleBefore,
            LocalDateTime retryBefore,
            int maxAttempts,
            MediaProcessingRecoveryCursor cursor,
            int batchSize
    ) {
        return imageAssetRepository.findProcessingRecoveryCandidates(
                        MediaCleanupStatus.ACTIVE,
                        TargetProcessingStatus.PROCESSING,
                        staleBefore,
                        retryBefore,
                        maxAttempts,
                        cursor.startedAt(),
                        cursor.assetId(),
                        Limit.of(batchSize)
                ).stream()
                .map(candidate -> new MediaProcessingRecoveryCandidate(
                        candidate.getAssetId(),
                        candidate.getJobId(),
                        candidate.getStartedAt()
                ))
                .toList();
    }
}
