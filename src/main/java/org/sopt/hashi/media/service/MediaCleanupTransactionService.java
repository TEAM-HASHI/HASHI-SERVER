package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaCleanupTransactionService {

    private final ImageAssetRepository repository;
    private final MediaCleanupEligibilityPolicy policy;
    private final MediaCleanupProperties properties;
    private final Clock clock;

    public MediaCleanupTransactionService(ImageAssetRepository repository, MediaCleanupEligibilityPolicy policy,
                                          MediaCleanupProperties properties, @Qualifier("japanClock") Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEligible(long assetId, UUID publicId) {
        if (!properties.enabled()) {
            return false;
        }
        return repository.findById(assetId).filter(asset -> asset.getPublicId().equals(publicId))
                .map(asset -> policy.isEligible(asset, LocalDateTime.now(clock))).orElse(false);
    }

    @Transactional
    public Optional<MediaPurgeWork> begin(long assetId, UUID publicId) {
        if (!properties.canDelete()) {
            return Optional.empty();
        }
        ImageAsset asset = repository.findByIdForUpdate(assetId).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (asset == null || !asset.getPublicId().equals(publicId) || !policy.isEligible(asset, now)) {
            return Optional.empty();
        }
        asset.beginPurge(UUID.randomUUID(), now);
        return Optional.of(work(asset));
    }

    @Transactional(readOnly = true)
    public boolean isResumable(MediaPurgeWork work) {
        if (!properties.enabled()) {
            return false;
        }
        return repository.findById(work.assetId())
                .map(asset -> canResume(asset, work, LocalDateTime.now(clock))).orElse(false);
    }

    @Transactional
    public Optional<MediaPurgeWork> resume(MediaPurgeWork work) {
        if (!properties.canDelete()) {
            return Optional.empty();
        }
        ImageAsset asset = repository.findByIdForUpdate(work.assetId()).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (asset == null || !canResume(asset, work, now)) {
            return Optional.empty();
        }
        asset.resumePurge(work.purgeToken(), now, now.minus(properties.retryInterval()));
        return Optional.of(work(asset));
    }

    /** S3에서 두 prefix의 삭제 완료를 확인한 호출자만 실행한다. */
    @Transactional
    public MediaCleanupOutcome finish(MediaPurgeWork work) {
        if (!properties.canDelete()) {
            return MediaCleanupOutcome.SKIPPED;
        }
        ImageAsset asset = repository.findByIdForUpdate(work.assetId()).orElse(null);
        if (asset == null || !matches(asset, work) || !policy.hasCanonicalOriginal(asset)) {
            return MediaCleanupOutcome.SKIPPED;
        }
        // 첫 완료 뒤 BOUND FAILED가 RETIRED로 바뀌었어도 중복 완료로 tombstone을 지우지 않는다.
        if (asset.getCleanupStatus() == MediaCleanupStatus.PURGED) {
            return MediaCleanupOutcome.ALREADY_PURGED;
        }
        if (!asset.completePurge(work.purgeToken(), LocalDateTime.now(clock))) {
            return MediaCleanupOutcome.SKIPPED;
        }
        if (!asset.mustRetainPurgeTombstone()) {
            repository.delete(asset);
        }
        return MediaCleanupOutcome.PURGED;
    }

    private boolean canResume(ImageAsset asset, MediaPurgeWork work, LocalDateTime now) {
        return matches(asset, work) && policy.hasCanonicalOriginal(asset)
                && policy.hasElapsedUploadWindow(asset, now)
                && asset.canResumePurge(work.purgeToken(), now, now.minus(properties.retryInterval()));
    }

    private boolean matches(ImageAsset asset, MediaPurgeWork work) {
        return asset.getPublicId().equals(work.publicId()) && Objects.equals(asset.getPurgeToken(), work.purgeToken());
    }

    private MediaPurgeWork work(ImageAsset asset) {
        return new MediaPurgeWork(asset.getId(), asset.getPublicId(), asset.getPurgeToken());
    }
}
