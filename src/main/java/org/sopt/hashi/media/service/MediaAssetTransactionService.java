package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPipelineConfig;
import org.sopt.hashi.media.domain.MediaPipelineConfigRepository;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.job.MediaProcessingJobIdFactory;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.spec.MediaSpecSnapshot;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaAssetTransactionService {

    private static final EnumSet<ImageProcessingStatus> IDEMPOTENT_COMPLETE_STATUSES =
            EnumSet.of(ImageProcessingStatus.PROCESSING, ImageProcessingStatus.READY);

    private final ImageAssetRepository imageAssetRepository;
    private final MediaPipelineConfigRepository pipelineConfigRepository;
    private final MediaSpecRegistry mediaSpecRegistry;
    private final MediaProcessingJobIdFactory jobIdFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public MediaAssetTransactionService(ImageAssetRepository imageAssetRepository,
                                        MediaPipelineConfigRepository pipelineConfigRepository,
                                        MediaSpecRegistry mediaSpecRegistry,
                                        MediaProcessingJobIdFactory jobIdFactory,
                                        ApplicationEventPublisher eventPublisher,
                                        @Qualifier("japanClock") Clock clock) {
        this.imageAssetRepository = imageAssetRepository;
        this.pipelineConfigRepository = pipelineConfigRepository;
        this.mediaSpecRegistry = mediaSpecRegistry;
        this.jobIdFactory = jobIdFactory;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void assertIssuanceAvailable() {
        requireAvailableSpec(lockPipelineConfig());
    }

    @Transactional
    public void createAssets(CurrentActor actor, MediaPurpose purpose, List<PreparedMediaAsset> uploads) {
        requireAvailableSpec(lockPipelineConfig());
        MediaOwnerType ownerType = toOwnerType(actor.type());
        List<ImageAsset> assets = uploads.stream()
                .map(upload -> ImageAsset.createDirectUpload(
                        upload.assetId(),
                        purpose,
                        ownerType,
                        actor.subjectId(),
                        upload.objectKey(),
                        upload.contentType(),
                        upload.contentLength(),
                        upload.uploadExpiresAt()
                ))
                .toList();
        imageAssetRepository.saveAll(assets);
    }

    @Transactional(readOnly = true)
    public List<OwnedAssetSnapshot> loadOwnedAssets(CurrentActor actor, List<UUID> assetIds) {
        List<ImageAsset> assets = imageAssetRepository.findAllByPublicIdIn(assetIds);
        Map<UUID, ImageAsset> assetsByPublicId = requireOwnedAssets(actor, assetIds, assets);
        return assetIds.stream()
                .map(assetId -> toSnapshot(assetsByPublicId.get(assetId)))
                .toList();
    }

    @Transactional
    public List<OwnedAssetSnapshot> completeAssets(
            CurrentActor actor,
            List<UUID> assetIds,
            Map<UUID, OriginalObjectMetadata> metadataByAssetId
    ) {
        Optional<MediaPipelineConfig> config = lockPipelineConfig();
        List<ImageAsset> lockedAssets = imageAssetRepository.findAllByPublicIdInForUpdate(assetIds);
        Map<UUID, ImageAsset> assetsByPublicId = requireOwnedAssets(actor, assetIds, lockedAssets);
        LocalDateTime now = LocalDateTime.now(clock);

        List<ImageAsset> pendingAssets = assetIds.stream()
                .map(assetsByPublicId::get)
                .peek(asset -> validateCompletable(asset, now))
                .filter(asset -> asset.getProcessingStatus() == ImageProcessingStatus.PENDING_UPLOAD)
                .toList();

        if (!pendingAssets.isEmpty()) {
            MediaSpecSnapshot spec = requireAvailableSpec(config);
            pendingAssets.forEach(asset -> beginProcessing(asset, metadataByAssetId, spec, now));
        }

        return assetIds.stream()
                .map(assetId -> toSnapshot(assetsByPublicId.get(assetId)))
                .toList();
    }

    private Optional<MediaPipelineConfig> lockPipelineConfig() {
        return pipelineConfigRepository.findByIdForShare(MediaPipelineConfig.SINGLETON_ID);
    }

    private MediaSpecSnapshot requireAvailableSpec(Optional<MediaPipelineConfig> configOptional) {
        MediaPipelineConfig config = configOptional
                .orElseThrow(() -> new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE));
        MediaSpecSnapshot snapshot = mediaSpecRegistry.find(config.getCurrentSpecVersion())
                .orElseThrow(() -> new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE));
        boolean isAvailable = config.isIssuanceEnabled()
                && config.matches(snapshot.version(), snapshot.digest());
        if (!isAvailable) {
            throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
        return snapshot;
    }

    private Map<UUID, ImageAsset> requireOwnedAssets(CurrentActor actor, Collection<UUID> requestedIds,
                                                     List<ImageAsset> assets) {
        MediaOwnerType ownerType = toOwnerType(actor.type());
        Map<UUID, ImageAsset> assetsByPublicId = assets.stream()
                .filter(asset -> asset.isOwnedBy(ownerType, actor.subjectId()))
                .collect(Collectors.toMap(ImageAsset::getPublicId, Function.identity()));
        if (assetsByPublicId.size() != requestedIds.size()
                || !assetsByPublicId.keySet().containsAll(requestedIds)) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
        return assetsByPublicId;
    }

    private void validateCompletable(ImageAsset asset, LocalDateTime now) {
        if (asset.getCleanupStatus() != MediaCleanupStatus.ACTIVE) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
        if (asset.getProcessingStatus() == ImageProcessingStatus.EXPIRED
                || (asset.getProcessingStatus() == ImageProcessingStatus.PENDING_UPLOAD
                && asset.isUploadExpired(now))) {
            throw new BusinessException(MediaErrorCode.ASSET_EXPIRED);
        }
        if (asset.getProcessingStatus() == ImageProcessingStatus.FAILED) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
        boolean isCompletable = asset.getProcessingStatus() == ImageProcessingStatus.PENDING_UPLOAD
                || IDEMPOTENT_COMPLETE_STATUSES.contains(asset.getProcessingStatus());
        if (!isCompletable) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
    }

    private void beginProcessing(ImageAsset asset,
                                 Map<UUID, OriginalObjectMetadata> metadataByAssetId,
                                 MediaSpecSnapshot spec,
                                 LocalDateTime startedAt) {
        OriginalObjectMetadata metadata = metadataByAssetId.get(asset.getPublicId());
        if (metadata == null) {
            throw new BusinessException(MediaErrorCode.UPLOAD_NOT_FOUND);
        }
        validateMetadata(asset, metadata);

        UUID jobId = jobIdFactory.create(
                asset.getPublicId(),
                metadata.versionId(),
                spec.version()
        );
        asset.beginInitialProcessing(
                metadata.versionId(),
                metadata.eTag(),
                spec.version(),
                spec.digest(),
                jobId,
                startedAt
        );
        eventPublisher.publishEvent(new MediaProcessingRequestedEvent(asset.getPublicId(), jobId));
    }

    private void validateMetadata(ImageAsset asset, OriginalObjectMetadata metadata) {
        boolean hasSourceIdentity = hasText(metadata.versionId()) && hasText(metadata.eTag());
        boolean matchesDeclaration = asset.getOriginalObjectKey().equals(metadata.objectKey())
                && asset.getDeclaredBytes() == metadata.contentLength()
                && asset.getDeclaredContentType().equalsIgnoreCase(metadata.contentType());
        if (!hasSourceIdentity || !matchesDeclaration) {
            throw new BusinessException(MediaErrorCode.UPLOAD_METADATA_MISMATCH);
        }
    }

    private OwnedAssetSnapshot toSnapshot(ImageAsset asset) {
        return new OwnedAssetSnapshot(
                asset.getPublicId(),
                asset.getOriginalObjectKey(),
                asset.getDeclaredContentType(),
                asset.getDeclaredBytes(),
                asset.getUploadExpiresAt(),
                asset.getProcessingStatus(),
                asset.getCleanupStatus()
        );
    }

    private MediaOwnerType toOwnerType(ActorType actorType) {
        return switch (actorType) {
            case USER -> MediaOwnerType.USER;
            case ADMIN -> MediaOwnerType.ADMIN;
            case ONBOARDING -> MediaOwnerType.ONBOARDING;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
