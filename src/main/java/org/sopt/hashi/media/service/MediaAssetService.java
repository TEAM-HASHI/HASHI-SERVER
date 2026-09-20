package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.dto.CompleteMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsResponse;
import org.sopt.hashi.media.dto.MediaAssetStatusResponse;
import org.sopt.hashi.media.dto.MediaAssetStatusesResponse;
import org.sopt.hashi.media.dto.MediaUploadResponse;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorage;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorageProperties;
import org.sopt.hashi.media.internal.storage.MediaStorageUnavailableException;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.media.internal.storage.PresignedOriginalUpload;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MediaAssetService {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final CurrentActorProvider currentActorProvider;
    private final MediaPurposeAccessPolicy purposeAccessPolicy;
    private final MediaAssetTransactionService transactionService;
    private final MediaOriginalStorage originalStorage;
    private final MediaOriginalStorageProperties storageProperties;
    private final Clock clock;

    public MediaAssetService(CurrentActorProvider currentActorProvider,
                             MediaPurposeAccessPolicy purposeAccessPolicy,
                             MediaAssetTransactionService transactionService,
                             MediaOriginalStorage originalStorage,
                             MediaOriginalStorageProperties storageProperties,
                             @Qualifier("japanClock") Clock clock) {
        this.currentActorProvider = currentActorProvider;
        this.purposeAccessPolicy = purposeAccessPolicy;
        this.transactionService = transactionService;
        this.originalStorage = originalStorage;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    public CreateMediaAssetsResponse createAssets(CreateMediaAssetsRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        validatePurpose(actor, request);
        validateFiles(request.files());
        transactionService.assertIssuanceAvailable();

        LocalDateTime issuedAt = LocalDateTime.now(clock);
        List<PreparedUpload> preparedUploads = request.files().stream()
                .map(file -> prepareUpload(file, issuedAt))
                .toList();
        transactionService.createAssets(
                actor,
                request.purpose(),
                preparedUploads.stream().map(PreparedUpload::asset).toList()
        );

        log.info("media asset upload issued. actorType={}, purpose={}, count={}",
                actor.type(), request.purpose(), preparedUploads.size());
        return new CreateMediaAssetsResponse(preparedUploads.stream()
                .map(this::toUploadResponse)
                .toList());
    }

    public MediaAssetStatusesResponse completeAssets(CompleteMediaAssetsRequest request) {
        validateAssetIds(request.assetIds());
        CurrentActor actor = currentActorProvider.currentActor();
        List<OwnedAssetSnapshot> snapshots = transactionService.loadOwnedAssets(actor, request.assetIds());
        validateSnapshotStatesBeforeHead(snapshots);
        boolean hasPendingUploads = snapshots.stream()
                .anyMatch(snapshot -> snapshot.status() == ImageProcessingStatus.PENDING_UPLOAD);
        if (hasPendingUploads) {
            transactionService.assertIssuanceAvailable();
        }

        Map<UUID, OriginalObjectMetadata> metadataByAssetId = inspectPendingUploads(snapshots);
        List<OwnedAssetSnapshot> completed = transactionService.completeAssets(
                actor,
                request.assetIds(),
                metadataByAssetId
        );
        log.info("media asset upload completed. actorType={}, count={}, processingCount={}",
                actor.type(), completed.size(), metadataByAssetId.size());
        return toStatusesResponse(completed);
    }

    public MediaAssetStatusesResponse getAssetStatuses(List<UUID> assetIds) {
        validateAssetIds(assetIds);
        CurrentActor actor = currentActorProvider.currentActor();
        return toStatusesResponse(transactionService.loadOwnedAssets(actor, assetIds));
    }

    private void validatePurpose(CurrentActor actor, CreateMediaAssetsRequest request) {
        if (!purposeAccessPolicy.isAllowed(actor.type(), request.purpose())) {
            throw new BusinessException(MediaErrorCode.PURPOSE_FORBIDDEN);
        }
    }

    private void validateFiles(List<CreateMediaAssetsRequest.FileRequest> files) {
        if (files.size() > storageProperties.maxFilesPerRequest()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        files.forEach(file -> {
            if (!SUPPORTED_CONTENT_TYPES.contains(file.contentType())) {
                throw new BusinessException(MediaErrorCode.UNSUPPORTED_FILE_TYPE);
            }
            if (file.fileSize() > storageProperties.maxFileSize().toBytes()) {
                throw new BusinessException(MediaErrorCode.FILE_SIZE_EXCEEDED);
            }
        });
    }

    private PreparedUpload prepareUpload(CreateMediaAssetsRequest.FileRequest file, LocalDateTime issuedAt) {
        UUID assetId = UUID.randomUUID();
        String objectKey = "media/originals/%s/original".formatted(assetId);
        try {
            PresignedOriginalUpload upload = originalStorage.createPresignedUpload(
                    objectKey,
                    file.contentType(),
                    file.fileSize()
            );
            PreparedMediaAsset asset = new PreparedMediaAsset(
                    assetId,
                    objectKey,
                    file.contentType(),
                    file.fileSize(),
                    issuedAt.plusSeconds(upload.expiresInSeconds())
            );
            return new PreparedUpload(asset, upload);
        } catch (MediaStorageUnavailableException e) {
            throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
    }

    private void validateAssetIds(List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty() || assetIds.size() > 10
                || assetIds.stream().anyMatch(assetId -> assetId == null)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (new HashSet<>(assetIds).size() != assetIds.size()) {
            throw new BusinessException(MediaErrorCode.DUPLICATE_ASSET);
        }
    }

    private void validateSnapshotStatesBeforeHead(List<OwnedAssetSnapshot> snapshots) {
        LocalDateTime now = LocalDateTime.now(clock);
        snapshots.forEach(snapshot -> {
            if (snapshot.cleanupStatus() != MediaCleanupStatus.ACTIVE) {
                throw new BusinessException(MediaErrorCode.INVALID_STATE);
            }
            if (snapshot.status() == ImageProcessingStatus.EXPIRED
                    || (snapshot.status() == ImageProcessingStatus.PENDING_UPLOAD
                    && !now.isBefore(snapshot.uploadExpiresAt()))) {
                throw new BusinessException(MediaErrorCode.ASSET_EXPIRED);
            }
            if (snapshot.status() == ImageProcessingStatus.FAILED) {
                throw new BusinessException(MediaErrorCode.INVALID_STATE);
            }
        });
    }

    private Map<UUID, OriginalObjectMetadata> inspectPendingUploads(List<OwnedAssetSnapshot> snapshots) {
        Map<UUID, OriginalObjectMetadata> metadataByAssetId = new LinkedHashMap<>();
        for (OwnedAssetSnapshot snapshot : snapshots) {
            if (snapshot.status() != ImageProcessingStatus.PENDING_UPLOAD) {
                continue;
            }
            try {
                OriginalObjectMetadata metadata = originalStorage.findObjectMetadata(snapshot.objectKey())
                        .orElseThrow(() -> new BusinessException(MediaErrorCode.UPLOAD_NOT_FOUND));
                validateMetadata(snapshot, metadata);
                metadataByAssetId.put(snapshot.assetId(), metadata);
            } catch (MediaStorageUnavailableException e) {
                throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
            }
        }
        return metadataByAssetId;
    }

    private void validateMetadata(OwnedAssetSnapshot snapshot, OriginalObjectMetadata metadata) {
        boolean hasSourceIdentity = MediaSourceIdentity.isValid(metadata.versionId(), metadata.eTag());
        boolean matchesDeclaration = snapshot.objectKey().equals(metadata.objectKey())
                && snapshot.declaredBytes() == metadata.contentLength()
                && snapshot.declaredContentType().equalsIgnoreCase(metadata.contentType());
        if (!hasSourceIdentity || !matchesDeclaration) {
            throw new BusinessException(MediaErrorCode.UPLOAD_METADATA_MISMATCH);
        }
    }

    private MediaUploadResponse toUploadResponse(PreparedUpload preparedUpload) {
        PreparedMediaAsset asset = preparedUpload.asset();
        PresignedOriginalUpload upload = preparedUpload.upload();
        return new MediaUploadResponse(
                asset.assetId(),
                ImageProcessingStatus.PENDING_UPLOAD,
                upload.uploadUrl(),
                upload.requiredHeaders(),
                upload.expectedContentLength(),
                upload.expiresInSeconds(),
                upload.uploadMethod()
        );
    }

    private MediaAssetStatusesResponse toStatusesResponse(List<OwnedAssetSnapshot> snapshots) {
        return new MediaAssetStatusesResponse(snapshots.stream()
                .map(snapshot -> new MediaAssetStatusResponse(snapshot.assetId(), snapshot.status()))
                .toList());
    }

    private record PreparedUpload(PreparedMediaAsset asset, PresignedOriginalUpload upload) {
    }
}
