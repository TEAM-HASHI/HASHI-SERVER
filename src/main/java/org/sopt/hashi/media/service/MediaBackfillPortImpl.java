package org.sopt.hashi.media.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillClaim;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.MediaBackfillProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class MediaBackfillPortImpl implements MediaBackfillPort {

    private final ImageAssetRepository assetRepository;
    private final MediaBackfillProperties properties;
    private final MediaBackfillPreparationService preparationService;

    MediaBackfillPortImpl(ImageAssetRepository assetRepository, MediaBackfillProperties properties,
                         MediaBackfillPreparationService preparationService) {
        this.assetRepository = assetRepository;
        this.properties = properties;
        this.preparationService = preparationService;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public MediaBackfillInspectionInfo inspect(MediaBackfillReference reference) {
        return preparationService.inspect(reference);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public MediaBackfillAssetInfo prepare(MediaBackfillReference reference, String expectedIdentityHash) {
        return preparationService.prepare(reference, expectedIdentityHash);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void claimReady(Collection<MediaBackfillClaim> claims) {
        if (!properties.enabled()) {
            throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("backfill claim requires a write transaction");
        }
        List<MediaBackfillClaim> normalized = List.copyOf(claims);
        Set<UUID> assetIds = distinctIds(normalized);
        if (assetIds.isEmpty()) {
            return;
        }
        Map<UUID, ImageAsset> assets = lockAssets(assetIds);
        normalized.forEach(claim -> validate(assets.get(claim.assetId()), claim));
        normalized.forEach(claim -> assets.get(claim.assetId()).bind());
    }

    private Set<UUID> distinctIds(List<MediaBackfillClaim> claims) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (MediaBackfillClaim claim : claims) {
            if (!ids.add(claim.assetId())) {
                throw new BusinessException(MediaErrorCode.DUPLICATE_ASSET);
            }
        }
        return ids;
    }

    private Map<UUID, ImageAsset> lockAssets(Set<UUID> assetIds) {
        List<ImageAssetRepository.AssetIdentity> identities = assetRepository.findIdentitiesByPublicIdIn(assetIds);
        if (identities.size() != assetIds.size()) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
        List<Long> internalIds = identities.stream().map(ImageAssetRepository.AssetIdentity::getId).sorted().toList();
        List<ImageAsset> assets = assetRepository.findAllByIdInForUpdate(internalIds);
        if (assets.size() != assetIds.size()) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
        return assets.stream().collect(Collectors.toMap(ImageAsset::getPublicId, Function.identity()));
    }

    private void validate(ImageAsset asset, MediaBackfillClaim claim) {
        boolean systemBackfill = asset.getCreationOrigin() == MediaCreationOrigin.SYSTEM_BACKFILL
                && asset.isOwnedBy(MediaOwnerType.SYSTEM_BACKFILL, null)
                && asset.getCreatorActorType() == null && asset.getCreatorSubjectId() == null;
        if (!systemBackfill) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
        boolean matches = asset.getPurpose() == MediaPurpose.valueOf(claim.purpose().name())
                && claim.identityHash().equals(asset.getBackfillIdentityHash());
        boolean ready = asset.getProcessingStatus() == ImageProcessingStatus.READY
                && asset.getCleanupStatus() == MediaCleanupStatus.ACTIVE;
        if (!matches || !ready) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
        if (asset.getBindingStatus() != ImageBindingStatus.UNBOUND) {
            throw new BusinessException(MediaErrorCode.ALREADY_BOUND);
        }
    }
}
