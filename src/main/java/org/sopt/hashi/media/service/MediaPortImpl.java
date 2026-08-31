package org.sopt.hashi.media.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImage.Candidate;
import org.sopt.hashi.media.MediaImage.Source;
import org.sopt.hashi.media.MediaImage.SourceSet;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageAssetRepository.AssetImageProjection;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.ImageRenditionRepository;
import org.sopt.hashi.media.domain.ImageRenditionRepository.RenditionImageProjection;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.spec.MediaRoleSpec;
import org.sopt.hashi.media.internal.spec.MediaSpecDefinition;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Component
@Transactional(readOnly = true)
class MediaPortImpl implements MediaPort {

    private static final EnumSet<ImageProcessingStatus> REVIEW_CLAIMABLE_STATUSES =
            EnumSet.of(ImageProcessingStatus.PROCESSING, ImageProcessingStatus.READY);

    private final ImageAssetRepository imageAssetRepository;
    private final ImageRenditionRepository imageRenditionRepository;
    private final CurrentActorProvider currentActorProvider;
    private final MediaPurposeAccessPolicy purposeAccessPolicy;
    private final MediaSpecRegistry mediaSpecRegistry;
    private final FileStorage fileStorage;

    MediaPortImpl(ImageAssetRepository imageAssetRepository,
                  ImageRenditionRepository imageRenditionRepository,
                  CurrentActorProvider currentActorProvider,
                  MediaPurposeAccessPolicy purposeAccessPolicy,
                  MediaSpecRegistry mediaSpecRegistry,
                  FileStorage fileStorage) {
        this.imageAssetRepository = imageAssetRepository;
        this.imageRenditionRepository = imageRenditionRepository;
        this.currentActorProvider = currentActorProvider;
        this.purposeAccessPolicy = purposeAccessPolicy;
        this.mediaSpecRegistry = mediaSpecRegistry;
        this.fileStorage = fileStorage;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileBindings(Collection<MediaAssetUse> claims, Collection<MediaAssetUse> retires) {
        List<MediaAssetUse> normalizedClaims = copyUses(claims);
        List<MediaAssetUse> normalizedRetires = copyUses(retires);
        Set<UUID> allIds = requireDistinctIds(normalizedClaims, normalizedRetires);
        if (allIds.isEmpty()) {
            return;
        }

        CurrentActor actor = currentActorProvider.currentActor();
        normalizedClaims.forEach(use -> requirePurposeAllowed(actor, use.purpose()));
        normalizedRetires.forEach(use -> requirePurposeAllowed(actor, use.purpose()));

        List<ImageAssetRepository.AssetIdentity> identities =
                imageAssetRepository.findIdentitiesByPublicIdIn(allIds);
        if (identities.size() != allIds.size()) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
        List<Long> internalIds = identities.stream()
                .map(ImageAssetRepository.AssetIdentity::getId)
                .sorted()
                .toList();
        List<ImageAsset> assets = imageAssetRepository.findAllByIdInForUpdate(internalIds);
        if (assets.size() != allIds.size()) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
        Map<UUID, ImageAsset> assetsById = assets.stream()
                .collect(Collectors.toMap(ImageAsset::getPublicId, Function.identity()));

        MediaOwnerType ownerType = toOwnerType(actor.type());
        normalizedClaims.forEach(use -> requireClaimOwner(
                assetsById.get(use.assetId()), ownerType, actor.subjectId()));
        normalizedClaims.forEach(use -> validateClaimState(
                assetsById.get(use.assetId()), use.purpose()));
        normalizedRetires.forEach(use -> validateRetire(assetsById.get(use.assetId()), use.purpose()));

        normalizedClaims.forEach(use -> assetsById.get(use.assetId()).bind());
        normalizedRetires.forEach(use -> assetsById.get(use.assetId()).retire());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void claimOnboardingProfile(UUID assetId, Long newUserId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (newUserId == null || newUserId < 1) {
            throw new IllegalArgumentException("newUserId must be positive");
        }

        CurrentActor actor = currentActorProvider.currentActor();
        if (actor.type() != ActorType.ONBOARDING) {
            throw new BusinessException(MediaErrorCode.PURPOSE_FORBIDDEN);
        }
        requirePurposeAllowed(actor, MediaAssetPurpose.PROFILE);

        ImageAssetRepository.AssetIdentity identity = imageAssetRepository
                .findIdentitiesByPublicIdIn(Set.of(assetId)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(MediaErrorCode.ASSET_NOT_FOUND));
        ImageAsset asset = imageAssetRepository.findByIdForUpdate(identity.getId())
                .orElseThrow(() -> new BusinessException(MediaErrorCode.ASSET_NOT_FOUND));
        requireClaimOwner(asset, MediaOwnerType.ONBOARDING, actor.subjectId());
        validateClaimState(asset, MediaAssetPurpose.PROFILE);
        asset.handoffOwnerAndBind(
                MediaOwnerType.ONBOARDING,
                actor.subjectId(),
                MediaOwnerType.USER,
                newUserId);
    }

    @Override
    public Map<MediaImageRequest, MediaImage> findImages(Collection<MediaImageRequest> requests) {
        List<MediaImageRequest> normalized = copyRequests(requests);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        Set<UUID> assetIds = normalized.stream()
                .map(MediaImageRequest::assetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, AssetImageProjection> assetsById = imageAssetRepository
                .findImageProjectionsByPublicIdIn(assetIds).stream()
                .collect(Collectors.toMap(AssetImageProjection::getPublicId, Function.identity()));
        Set<ImageRole> roles = normalized.stream()
                .map(request -> ImageRole.valueOf(request.role().name()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ImageRole.class)));
        Map<RenditionKey, List<RenditionImageProjection>> renditionsByKey =
                imageRenditionRepository.findActiveImageProjections(assetIds, roles).stream()
                        .collect(Collectors.groupingBy(
                                rendition -> new RenditionKey(
                                        rendition.getAssetId(), rendition.getRole()),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        Map<MediaImageRequest, MediaImage> result = new LinkedHashMap<>();
        normalized.forEach(request -> project(
                        assetsById.get(request.assetId()),
                        renditionsByKey.getOrDefault(
                                new RenditionKey(
                                        request.assetId(),
                                        ImageRole.valueOf(request.role().name())),
                                List.of()),
                        request)
                .ifPresent(image -> result.put(request, image)));
        return Map.copyOf(result);
    }

    private java.util.Optional<MediaImage> project(
            AssetImageProjection asset,
            List<RenditionImageProjection> renditionProjections,
            MediaImageRequest request
    ) {
        if (asset == null
                || asset.getBindingStatus() != ImageBindingStatus.BOUND
                || asset.getCleanupStatus() == MediaCleanupStatus.PURGING) {
            return java.util.Optional.empty();
        }
        ImageProcessingStatus processingStatus = asset.getProcessingStatus();
        if (processingStatus != ImageProcessingStatus.PROCESSING
                && processingStatus != ImageProcessingStatus.READY
                && processingStatus != ImageProcessingStatus.FAILED) {
            return java.util.Optional.empty();
        }

        Integer specVersion = projectionSpecVersion(asset);
        MediaSpecDefinition definition = specVersion == null
                ? null
                : mediaSpecRegistry.findDefinition(specVersion).orElse(null);
        ImageRole internalRole = ImageRole.valueOf(request.role().name());
        if (definition == null
                || !matchesPackagedDigest(asset, definition)
                || !supports(definition, asset.getPurpose(), internalRole)) {
            return java.util.Optional.empty();
        }

        MediaImageStatus status = MediaImageStatus.valueOf(processingStatus.name());
        if (status != MediaImageStatus.READY) {
            return java.util.Optional.of(new MediaImage(
                    asset.getPublicId(), request.role(), status, null, List.of()));
        }

        List<RenditionImageProjection> renditions = renditionProjections.stream()
                .sorted(java.util.Comparator.comparing(RenditionImageProjection::getMimeType)
                        .thenComparingInt(RenditionImageProjection::getWidth))
                .toList();
        if (renditions.isEmpty()) {
            return java.util.Optional.empty();
        }

        MediaRoleSpec roleSpec = definition.roleSpecs().get(internalRole);
        RenditionImageProjection defaultRendition = renditions.stream()
                .filter(rendition -> rendition.getWidth() == roleSpec.defaultWidth())
                .findFirst()
                .orElseGet(() -> renditions.getLast());
        Source defaultSource = toSource(defaultRendition);
        List<SourceSet> sourceSets = renditions.stream()
                .collect(Collectors.groupingBy(
                        RenditionImageProjection::getMimeType,
                        LinkedHashMap::new,
                        Collectors.toList()
                )).entrySet().stream()
                .map(entry -> new SourceSet(
                        entry.getKey(),
                        entry.getValue().stream().map(this::toCandidate).toList()
                ))
                .toList();
        return java.util.Optional.of(new MediaImage(
                asset.getPublicId(), request.role(), status, defaultSource, sourceSets));
    }

    private void requireClaimOwner(ImageAsset asset, MediaOwnerType actorType, Long actorSubjectId) {
        if (!asset.isOwnedBy(actorType, actorSubjectId)) {
            throw new BusinessException(MediaErrorCode.ASSET_NOT_FOUND);
        }
    }

    private void validateClaimState(ImageAsset asset, MediaAssetPurpose expectedPurpose) {
        if (asset.getPurpose() != MediaPurpose.valueOf(expectedPurpose.name())
                || asset.getCleanupStatus() != MediaCleanupStatus.ACTIVE) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
        if (asset.getBindingStatus() != ImageBindingStatus.UNBOUND) {
            throw new BusinessException(MediaErrorCode.ALREADY_BOUND);
        }
        boolean claimable = expectedPurpose == MediaAssetPurpose.REVIEW
                ? REVIEW_CLAIMABLE_STATUSES.contains(asset.getProcessingStatus())
                : asset.getProcessingStatus() == ImageProcessingStatus.READY;
        if (!claimable) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
    }

    private void validateRetire(ImageAsset asset, MediaAssetPurpose expectedPurpose) {
        boolean active = asset.getCleanupStatus() == MediaCleanupStatus.ACTIVE;
        boolean purgedFailure = asset.getCleanupStatus() == MediaCleanupStatus.PURGED
                && asset.getProcessingStatus() == ImageProcessingStatus.FAILED
                && asset.getObjectsPurgedAt() != null;
        if (asset.getPurpose() != MediaPurpose.valueOf(expectedPurpose.name())
                || asset.getBindingStatus() != ImageBindingStatus.BOUND
                || (!active && !purgedFailure)) {
            throw new BusinessException(MediaErrorCode.INVALID_STATE);
        }
    }

    private Set<UUID> requireDistinctIds(List<MediaAssetUse> claims, List<MediaAssetUse> retires) {
        List<UUID> requestedIds = new ArrayList<>();
        claims.stream().map(MediaAssetUse::assetId).forEach(requestedIds::add);
        retires.stream().map(MediaAssetUse::assetId).forEach(requestedIds::add);
        Set<UUID> distinct = new LinkedHashSet<>(requestedIds);
        if (distinct.size() != requestedIds.size()) {
            throw new BusinessException(MediaErrorCode.DUPLICATE_ASSET);
        }
        return distinct;
    }

    private List<MediaAssetUse> copyUses(Collection<MediaAssetUse> uses) {
        if (uses == null) {
            return List.of();
        }
        if (uses.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(MediaErrorCode.DUPLICATE_ASSET);
        }
        return List.copyOf(uses);
    }

    private List<MediaImageRequest> copyRequests(Collection<MediaImageRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (requests.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("media image requests must not contain null");
        }
        return List.copyOf(new LinkedHashSet<>(requests));
    }

    private Integer projectionSpecVersion(AssetImageProjection asset) {
        return switch (asset.getProcessingStatus()) {
            case READY -> asset.getActiveSpecVersion();
            case PROCESSING -> asset.getTargetSpecVersion();
            case FAILED -> asset.getLastFailureSpecVersion();
            default -> null;
        };
    }

    private boolean matchesPackagedDigest(
            AssetImageProjection asset,
            MediaSpecDefinition definition
    ) {
        return switch (asset.getProcessingStatus()) {
            case READY -> Objects.equals(asset.getActiveSpecDigest(), definition.digest());
            case PROCESSING -> Objects.equals(asset.getTargetSpecDigest(), definition.digest());
            case FAILED -> true;
            default -> false;
        };
    }

    private boolean supports(MediaSpecDefinition definition, MediaPurpose purpose, ImageRole role) {
        List<ImageRole> roles = definition.purposeRoles().get(purpose);
        return roles != null && roles.contains(role);
    }

    private Source toSource(RenditionImageProjection rendition) {
        return new Source(
                fileStorage.resolveFileUrl(rendition.getObjectKey()),
                rendition.getWidth(),
                rendition.getHeight(),
                rendition.getMimeType()
        );
    }

    private Candidate toCandidate(RenditionImageProjection rendition) {
        return new Candidate(
                fileStorage.resolveFileUrl(rendition.getObjectKey()),
                rendition.getWidth(),
                rendition.getHeight()
        );
    }

    private MediaOwnerType toOwnerType(ActorType actorType) {
        return switch (actorType) {
            case USER -> MediaOwnerType.USER;
            case ADMIN -> MediaOwnerType.ADMIN;
            case ONBOARDING -> MediaOwnerType.ONBOARDING;
        };
    }

    private void requirePurposeAllowed(CurrentActor actor, MediaAssetPurpose purpose) {
        if (!purposeAccessPolicy.isAllowed(actor.type(), MediaPurpose.valueOf(purpose.name()))) {
            throw new BusinessException(MediaErrorCode.PURPOSE_FORBIDDEN);
        }
    }

    private record RenditionKey(UUID assetId, ImageRole role) {
    }
}
