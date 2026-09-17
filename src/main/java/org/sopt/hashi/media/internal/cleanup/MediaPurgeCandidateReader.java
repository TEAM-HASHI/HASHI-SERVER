package org.sopt.hashi.media.internal.cleanup;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.service.MediaPurgeWork;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaPurgeCandidateReader {

    private final ImageAssetRepository repository;

    public MediaPurgeCandidateReader(ImageAssetRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<MediaPurgeCandidate> findBatch(LocalDateTime retryBefore, MediaPurgeCursor cursor, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("purge batch size must be between 1 and 1000");
        }
        return repository.findPurgeCandidates(MediaCleanupStatus.PURGING, retryBefore,
                        cursor.lastAttemptAt(), cursor.assetId(), Limit.of(batchSize)).stream()
                .map(candidate -> new MediaPurgeCandidate(new MediaPurgeWork(candidate.getAssetId(),
                        candidate.getPublicId(), candidate.getPurgeToken()), candidate.getLastAttemptAt()))
                .toList();
    }
}
