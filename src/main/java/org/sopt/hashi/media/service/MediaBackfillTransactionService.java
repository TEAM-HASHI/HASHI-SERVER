package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPipelineConfig;
import org.sopt.hashi.media.domain.MediaPipelineConfigRepository;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.BackfillOriginalCopy;
import org.sopt.hashi.media.internal.backfill.LegacyImageSource;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.spec.MediaSpecSnapshot;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaBackfillTransactionService {

    private final ImageAssetRepository assetRepository;
    private final MediaPipelineConfigRepository configRepository;
    private final MediaSpecRegistry specRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final MediaRecoveryProperties recoveryProperties;
    private final Clock clock;

    public MediaBackfillTransactionService(ImageAssetRepository assetRepository,
                                            MediaPipelineConfigRepository configRepository,
                                            MediaSpecRegistry specRegistry,
                                            ApplicationEventPublisher eventPublisher,
                                            MediaRecoveryProperties recoveryProperties,
                                            @Qualifier("japanClock") Clock clock) {
        this.assetRepository = assetRepository;
        this.configRepository = configRepository;
        this.specRegistry = specRegistry;
        this.eventPublisher = eventPublisher;
        this.recoveryProperties = recoveryProperties;
        this.clock = clock;
    }

    @Transactional
    public BackfillAssetSnapshot reserve(MediaPurpose purpose, String identityHash, LegacyImageSource source) {
        validateReservationInput(purpose, identityHash, source);
        Optional<MediaPipelineConfig> config = lockConfig();
        Optional<ImageAsset> existing = assetRepository.findByBackfillIdentityHash(identityHash);
        if (existing.isPresent()) {
            return reservationSnapshot(existing.get(), purpose, source);
        }
        requireAvailableSpec(config);
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createSystemBackfill(assetId, purpose,
                "media/originals/%s/original".formatted(assetId), source.contentType(), source.bytes(),
                LocalDateTime.now(clock).plus(recoveryProperties.pendingRetention()), identityHash);
        // unique 오류를 이 transaction 안에서 확정하고 외부 예약 Service가 재조회한다.
        assetRepository.saveAndFlush(asset);
        return BackfillAssetSnapshot.from(asset);
    }

    @Transactional(readOnly = true)
    public void assertIssuanceAvailable() {
        requireAvailableSpec(lockConfig());
    }

    @Transactional(readOnly = true)
    public Optional<BackfillAssetSnapshot> findReservation(
            MediaPurpose purpose, String identityHash, LegacyImageSource source) {
        validateReservationInput(purpose, identityHash, source);
        return assetRepository.findByBackfillIdentityHash(identityHash)
                .map(asset -> reservationSnapshot(asset, purpose, source));
    }

    /** copy의 exact version을 고정하고 job/EPR을 같은 transaction에 기록한다. */
    @Transactional
    public BackfillAssetSnapshot completeCopy(UUID assetId, BackfillOriginalCopy copy) {
        Objects.requireNonNull(assetId, "assetId is required");
        Objects.requireNonNull(copy, "copy is required");
        Optional<MediaPipelineConfig> config = lockConfig();
        ImageAssetRepository.AssetIdentity identity = assetRepository.findIdentitiesByPublicIdIn(Set.of(assetId))
                .stream().findFirst().orElseThrow(() -> new BusinessException(MediaErrorCode.ASSET_NOT_FOUND));
        ImageAsset asset = assetRepository.findByIdForUpdate(identity.getId())
                .orElseThrow(() -> new BusinessException(MediaErrorCode.ASSET_NOT_FOUND));
        requireSystemBackfill(asset);
        validateCopy(asset, copy);
        if (asset.getCleanupStatus() != MediaCleanupStatus.ACTIVE) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
        boolean alreadyProcessed = asset.getProcessingStatus() == ImageProcessingStatus.PROCESSING
                || asset.getProcessingStatus() == ImageProcessingStatus.READY;
        if (alreadyProcessed) {
            // 동시 copy의 다른 version이 늦게 도착해도 최초 고정 source와 job을 유지한다.
            return BackfillAssetSnapshot.from(asset);
        }
        boolean pendingExpired = asset.getProcessingStatus() == ImageProcessingStatus.PENDING_UPLOAD
                && asset.isUploadExpired(now());
        if (asset.getProcessingStatus() == ImageProcessingStatus.EXPIRED || pendingExpired) {
            throw new BusinessException(MediaErrorCode.ASSET_EXPIRED);
        }
        boolean pendingUnbound = asset.getProcessingStatus() == ImageProcessingStatus.PENDING_UPLOAD
                && asset.getBindingStatus() == ImageBindingStatus.UNBOUND;
        if (!pendingUnbound) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
        MediaSpecSnapshot spec = requireAvailableSpec(config);
        UUID jobId = MediaProcessingJobId.from(assetId, copy.versionId(), spec.version());
        asset.beginInitialProcessing(copy.versionId(), copy.eTag(), spec.version(), spec.digest(), jobId, now());
        eventPublisher.publishEvent(new MediaProcessingRequestedEvent(assetId, jobId));
        return BackfillAssetSnapshot.from(asset);
    }

    private BackfillAssetSnapshot reservationSnapshot(
            ImageAsset asset, MediaPurpose purpose, LegacyImageSource source) {
        requireSystemBackfill(asset);
        boolean matches = asset.getPurpose() == purpose && asset.getDeclaredBytes() == source.bytes()
                && asset.getDeclaredContentType().equals(source.contentType());
        if (!matches) {
            throw new BusinessException(MediaErrorCode.UPLOAD_METADATA_MISMATCH);
        }
        // terminal/purged identity도 반환한다. 자동 스캔이 같은 실패 asset을 재생성하지 않는다.
        return BackfillAssetSnapshot.from(asset);
    }

    private void validateCopy(ImageAsset asset, BackfillOriginalCopy copy) {
        boolean matches = MediaSourceIdentity.isValid(copy.versionId(), copy.eTag())
                && Objects.equals(asset.getBackfillIdentityHash(), copy.identityHash())
                && asset.getOriginalObjectKey().equals(copy.objectKey())
                && asset.getDeclaredContentType().equals(copy.contentType())
                && asset.getDeclaredBytes() == copy.bytes();
        if (!matches) {
            throw new BusinessException(MediaErrorCode.UPLOAD_METADATA_MISMATCH);
        }
    }

    private void requireSystemBackfill(ImageAsset asset) {
        boolean systemBackfill = asset.getCreationOrigin() == MediaCreationOrigin.SYSTEM_BACKFILL
                && asset.isOwnedBy(MediaOwnerType.SYSTEM_BACKFILL, null)
                && asset.getCreatorActorType() == null && asset.getCreatorSubjectId() == null;
        if (!systemBackfill) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
    }

    private void validateReservationInput(MediaPurpose purpose, String identityHash, LegacyImageSource source) {
        Objects.requireNonNull(purpose, "purpose is required");
        Objects.requireNonNull(source, "source is required");
        if (identityHash == null || !identityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backfill identity must be a lowercase SHA-256 digest");
        }
    }

    private Optional<MediaPipelineConfig> lockConfig() {
        return configRepository.findByIdForShare(MediaPipelineConfig.SINGLETON_ID);
    }

    private MediaSpecSnapshot requireAvailableSpec(Optional<MediaPipelineConfig> optionalConfig) {
        MediaPipelineConfig config = optionalConfig
                .orElseThrow(() -> new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE));
        MediaSpecSnapshot spec = specRegistry.find(config.getCurrentSpecVersion())
                .orElseThrow(() -> new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE));
        boolean available = config.isIssuanceEnabled() && config.matches(spec.version(), spec.digest());
        if (!available) {
            throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
        return spec;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
