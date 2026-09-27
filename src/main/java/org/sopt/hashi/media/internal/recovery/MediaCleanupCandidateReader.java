package org.sopt.hashi.media.internal.recovery;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaCleanupCandidateReader {

    private final ImageAssetRepository imageAssetRepository;

    public MediaCleanupCandidateReader(ImageAssetRepository imageAssetRepository) {
        this.imageAssetRepository = imageAssetRepository;
    }

    @Transactional(readOnly = true)
    public List<MediaCleanupCandidate> findUnboundBatch(
            MediaCreationOrigin creationOrigin,
            Collection<ImageProcessingStatus> processingStatuses,
            LocalDateTime updatedBefore,
            MediaCleanupCandidateCursor cursor,
            int batchSize
    ) {
        return findBatch(ImageBindingStatus.UNBOUND, creationOrigin, processingStatuses, updatedBefore,
                cursor, batchSize);
    }

    @Transactional(readOnly = true)
    public List<MediaCleanupCandidate> findFailedBoundBatch(
            MediaCreationOrigin creationOrigin,
            LocalDateTime updatedBefore,
            MediaCleanupCandidateCursor cursor,
            int batchSize
    ) {
        return findBatch(ImageBindingStatus.BOUND, creationOrigin, List.of(ImageProcessingStatus.FAILED),
                updatedBefore, cursor, batchSize);
    }

    private List<MediaCleanupCandidate> findBatch(ImageBindingStatus bindingStatus,
                                                 MediaCreationOrigin creationOrigin,
                                                 Collection<ImageProcessingStatus> processingStatuses,
                                                 LocalDateTime updatedBefore,
                                                 MediaCleanupCandidateCursor cursor, int batchSize) {
        if (processingStatuses == null || processingStatuses.isEmpty()) {
            throw new IllegalArgumentException("processingStatuses must not be empty");
        }
        return imageAssetRepository.findCleanupCandidates(
                        MediaCleanupStatus.ACTIVE,
                        bindingStatus,
                        List.copyOf(processingStatuses),
                        creationOrigin,
                        updatedBefore,
                        cursor.updatedAt(),
                        cursor.assetId(),
                        Limit.of(batchSize)
                ).stream()
                .map(candidate -> new MediaCleanupCandidate(
                        candidate.getAssetId(),
                        candidate.getPublicId(),
                        candidate.getProcessingStatus(),
                        candidate.getCreationOrigin(),
                        candidate.getUpdatedAt()
                ))
                .toList();
    }
}
