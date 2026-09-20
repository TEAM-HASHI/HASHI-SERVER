package org.sopt.hashi.media.service;

import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties.Mode;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorage;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "hashi.media.cleanup", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class MediaCleanupService {

    private final MediaCleanupTransactionService transactions;
    private final MediaCleanupStorage storage;
    private final MediaCleanupProperties properties;

    public MediaCleanupService(MediaCleanupTransactionService transactions, MediaCleanupStorage storage,
                                MediaCleanupProperties properties) {
        this.transactions = transactions;
        this.storage = storage;
        this.properties = properties;
    }

    public MediaCleanupOutcome clean(MediaCleanupCandidate candidate) {
        if (!properties.enabled()) {
            return MediaCleanupOutcome.SKIPPED;
        }
        requireNotInterrupted();
        if (properties.mode() == Mode.DRY_RUN) {
            return transactions.isEligible(candidate.assetId(), candidate.publicId())
                    ? MediaCleanupOutcome.WOULD_PURGE : MediaCleanupOutcome.SKIPPED;
        }
        return transactions.begin(candidate.assetId(), candidate.publicId())
                .map(this::purge).orElse(MediaCleanupOutcome.SKIPPED);
    }

    public MediaCleanupOutcome resume(MediaPurgeWork work) {
        if (!properties.enabled()) {
            return MediaCleanupOutcome.SKIPPED;
        }
        requireNotInterrupted();
        if (properties.mode() == Mode.DRY_RUN) {
            return transactions.isResumable(work) ? MediaCleanupOutcome.WOULD_PURGE : MediaCleanupOutcome.SKIPPED;
        }
        return transactions.resume(work).map(this::purge).orElse(MediaCleanupOutcome.SKIPPED);
    }

    private MediaCleanupOutcome purge(MediaPurgeWork work) {
        // begin/resume의 transaction은 이미 커밋됐다. 실패/중단이면 PURGING 기록을 남겨 재개한다.
        if (!storage.purgeAssetObjects(work.publicId()).complete()) {
            return MediaCleanupOutcome.INCOMPLETE;
        }
        requireNotInterrupted();
        return transactions.finish(work);
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new MediaCleanupStorageException(MediaCleanupStorageException.Reason.INTERRUPTED);
        }
    }
}
