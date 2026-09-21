package org.sopt.hashi.media.service;

import java.time.Duration;
import java.time.LocalDateTime;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.springframework.stereotype.Component;

@Component
public class MediaCleanupEligibilityPolicy {

    private final MediaRecoveryProperties retention;
    private final MediaCleanupProperties cleanup;

    public MediaCleanupEligibilityPolicy(MediaRecoveryProperties retention, MediaCleanupProperties cleanup) {
        this.retention = retention;
        this.cleanup = cleanup;
    }

    public boolean isEligible(ImageAsset asset, LocalDateTime now) {
        if (!asset.canStartPurge() || !hasCanonicalOriginal(asset) || asset.getUpdatedAt() == null) {
            return false;
        }
        Duration minimumAge = retentionFor(asset.getProcessingStatus(), asset.getCreationOrigin(),
                asset.getBindingStatus());
        return !asset.getUpdatedAt().isAfter(now.minus(minimumAge)) && hasElapsedUploadWindow(asset, now);
    }

    public boolean hasCanonicalOriginal(ImageAsset asset) {
        return ("media/originals/%s/original".formatted(asset.getPublicId())).equals(asset.getOriginalObjectKey());
    }

    public boolean hasElapsedUploadWindow(ImageAsset asset, LocalDateTime now) {
        Duration window = cleanup.uploadSafetyWindow();
        // 삭제 뒤 아직 유효한 업로드 URL로 파일이 다시 쓰이지 않도록 모든 상태에서 만료를 확인한다.
        return window != null && !asset.getUploadExpiresAt().isAfter(now.minus(window));
    }

    public Duration retentionFor(ImageProcessingStatus state, MediaCreationOrigin origin,
                                  ImageBindingStatus binding) {
        Duration age = switch (state) {
            case PENDING_UPLOAD, EXPIRED -> retention.pendingRetention();
            case READY -> retention.directUnboundReadyRetention();
            case FAILED -> retention.failedRetention();
            case PROCESSING -> throw new IllegalArgumentException("processing assets cannot be purged");
        };
        boolean unboundBackfill = origin == MediaCreationOrigin.SYSTEM_BACKFILL
                && binding == ImageBindingStatus.UNBOUND;
        if (unboundBackfill && retention.backfillUnboundRetention().compareTo(age) > 0) {
            return retention.backfillUnboundRetention();
        }
        return age;
    }
}
