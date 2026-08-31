package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageAssetRepository.AssetIdentity;
import org.sopt.hashi.media.domain.ImageAssetRepository.AssetImageProjection;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.ImageRenditionRepository;
import org.sopt.hashi.media.domain.ImageRenditionRepository.RenditionImageProjection;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;

@ExtendWith(MockitoExtension.class)
class MediaPortImplTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";

    @Mock
    private ImageAssetRepository imageAssetRepository;

    @Mock
    private ImageRenditionRepository imageRenditionRepository;

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private MediaPurposeAccessPolicy purposeAccessPolicy;

    @Mock
    private FileStorage fileStorage;

    private MediaPortImpl mediaPort;

    @BeforeEach
    void setUp() {
        mediaPort = new MediaPortImpl(
                imageAssetRepository,
                imageRenditionRepository,
                currentActorProvider,
                purposeAccessPolicy,
                new MediaSpecRegistry(new ObjectMapper()),
                fileStorage
        );
    }

    @Test
    void claim과_retire는_내부_PK_오름차순으로_잠근_뒤_함께_전이한다() {
        UUID claimId = UUID.randomUUID();
        UUID retireId = UUID.randomUUID();
        ImageAsset claimAsset = asset(
                claimId, MediaPurpose.RESTAURANT, MediaOwnerType.ADMIN, 3L,
                ImageProcessingStatus.READY, ImageBindingStatus.UNBOUND,
                MediaCleanupStatus.ACTIVE);
        ImageAsset retireAsset = asset(
                retireId, MediaPurpose.RESTAURANT_MENU, MediaOwnerType.ADMIN, 7L,
                ImageProcessingStatus.READY, ImageBindingStatus.BOUND,
                MediaCleanupStatus.ACTIVE);
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ADMIN, 3L));
        when(purposeAccessPolicy.isAllowed(ActorType.ADMIN, MediaPurpose.RESTAURANT))
                .thenReturn(true);
        when(purposeAccessPolicy.isAllowed(ActorType.ADMIN, MediaPurpose.RESTAURANT_MENU))
                .thenReturn(true);
        when(imageAssetRepository.findIdentitiesByPublicIdIn(anyCollection()))
                .thenReturn(List.of(identity(20L, claimId), identity(10L, retireId)));
        when(imageAssetRepository.findAllByIdInForUpdate(List.of(10L, 20L)))
                .thenReturn(List.of(retireAsset, claimAsset));

        mediaPort.reconcileBindings(
                List.of(new MediaAssetUse(claimId, MediaAssetPurpose.RESTAURANT)),
                List.of(new MediaAssetUse(retireId, MediaAssetPurpose.RESTAURANT_MENU))
        );

        verify(imageAssetRepository).findAllByIdInForUpdate(List.of(10L, 20L));
        verify(claimAsset).bind();
        verify(retireAsset).retire();
    }

    @Test
    void claim과_retire에_같은_asset이_있으면_조회와_변경_전에_거부한다() {
        UUID assetId = UUID.randomUUID();
        MediaAssetUse use = new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT);

        assertThatThrownBy(() -> mediaPort.reconcileBindings(List.of(use), List.of(use)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.DUPLICATE_ASSET);

        verify(currentActorProvider, never()).currentActor();
        verify(imageAssetRepository, never()).findIdentitiesByPublicIdIn(anyCollection());
    }

    @Test
    void 검증이_하나라도_실패하면_어떤_asset도_변경하지_않는다() {
        UUID validId = UUID.randomUUID();
        UUID invalidId = UUID.randomUUID();
        ImageAsset valid = asset(
                validId, MediaPurpose.RESTAURANT, MediaOwnerType.ADMIN, 3L,
                ImageProcessingStatus.READY, ImageBindingStatus.UNBOUND,
                MediaCleanupStatus.ACTIVE);
        ImageAsset invalid = asset(
                invalidId, MediaPurpose.RESTAURANT_MENU, MediaOwnerType.ADMIN, 3L,
                ImageProcessingStatus.PROCESSING, ImageBindingStatus.UNBOUND,
                MediaCleanupStatus.ACTIVE);
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ADMIN, 3L));
        when(purposeAccessPolicy.isAllowed(ActorType.ADMIN, MediaPurpose.RESTAURANT))
                .thenReturn(true);
        when(purposeAccessPolicy.isAllowed(ActorType.ADMIN, MediaPurpose.RESTAURANT_MENU))
                .thenReturn(true);
        when(imageAssetRepository.findIdentitiesByPublicIdIn(anyCollection()))
                .thenReturn(List.of(identity(1L, validId), identity(2L, invalidId)));
        when(imageAssetRepository.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(valid, invalid));

        assertThatThrownBy(() -> mediaPort.reconcileBindings(
                List.of(
                        new MediaAssetUse(validId, MediaAssetPurpose.RESTAURANT),
                        new MediaAssetUse(invalidId, MediaAssetPurpose.RESTAURANT_MENU)
                ),
                List.of()
        )).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.INVALID_STATE);

        verify(valid, never()).bind();
        verify(invalid, never()).bind();
    }

    @ParameterizedTest
    @EnumSource(value = ImageBindingStatus.class, names = {"UNBOUND", "BOUND"})
    void 전체_claim의_소유권을_앞선_asset_상태보다_먼저_확인한다(ImageBindingStatus bindingStatus) {
        UUID bannerId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        ImageAsset banner = asset(
                bannerId, MediaPurpose.MAGAZINE_BANNER, MediaOwnerType.ADMIN, 3L,
                bindingStatus == ImageBindingStatus.UNBOUND
                        ? ImageProcessingStatus.PROCESSING : ImageProcessingStatus.READY,
                bindingStatus, MediaCleanupStatus.ACTIVE);
        ImageAsset foreign = asset(
                foreignId, MediaPurpose.MAGAZINE_THUMBNAIL, MediaOwnerType.ADMIN, 7L,
                ImageProcessingStatus.READY, ImageBindingStatus.UNBOUND,
                MediaCleanupStatus.ACTIVE);
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ADMIN, 3L));
        when(purposeAccessPolicy.isAllowed(ActorType.ADMIN, MediaPurpose.MAGAZINE_BANNER))
                .thenReturn(true);
        when(purposeAccessPolicy.isAllowed(ActorType.ADMIN, MediaPurpose.MAGAZINE_THUMBNAIL))
                .thenReturn(true);
        when(imageAssetRepository.findIdentitiesByPublicIdIn(anyCollection()))
                .thenReturn(List.of(identity(1L, bannerId), identity(2L, foreignId)))
                .thenReturn(List.of(identity(1L, bannerId)));
        when(imageAssetRepository.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(banner, foreign));

        for (UUID deniedId : List.of(foreignId, missingId)) {
            assertThatThrownBy(() -> mediaPort.reconcileBindings(
                    List.of(
                            new MediaAssetUse(bannerId, MediaAssetPurpose.MAGAZINE_BANNER),
                            new MediaAssetUse(deniedId, MediaAssetPurpose.MAGAZINE_THUMBNAIL)
                    ), List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.ASSET_NOT_FOUND);
        }

        verify(banner, never()).bind();
        verify(foreign, never()).bind();
        verify(banner, never()).retire();
        verify(foreign, never()).retire();
    }

    @Test
    void 온보딩_PROFILE_asset은_새_USER에게_인계하며_BIND한다() {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = asset(
                assetId, MediaPurpose.PROFILE, MediaOwnerType.ONBOARDING, 99L,
                ImageProcessingStatus.READY, ImageBindingStatus.UNBOUND,
                MediaCleanupStatus.ACTIVE);
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ONBOARDING, 99L));
        when(purposeAccessPolicy.isAllowed(ActorType.ONBOARDING, MediaPurpose.PROFILE))
                .thenReturn(true);
        when(imageAssetRepository.findIdentitiesByPublicIdIn(Set.of(assetId)))
                .thenReturn(List.of(identity(5L, assetId)));
        when(imageAssetRepository.findByIdForUpdate(5L)).thenReturn(java.util.Optional.of(asset));

        mediaPort.claimOnboardingProfile(assetId, 7L);

        verify(asset).handoffOwnerAndBind(
                MediaOwnerType.ONBOARDING, 99L, MediaOwnerType.USER, 7L);
    }

    @Test
    void 정식_USER는_온보딩_PROFILE_인계_API를_사용할_수_없다() {
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.USER, 7L));

        assertThatThrownBy(() -> mediaPort.claimOnboardingProfile(UUID.randomUUID(), 7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PURPOSE_FORBIDDEN);

        verify(imageAssetRepository, never()).findIdentitiesByPublicIdIn(anyCollection());
    }

    @Test
    void READY_projection은_defaultWidth와_모든_candidate를_반환한다() {
        UUID assetId = UUID.randomUUID();
        MediaImageRequest request =
                new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_CARD);
        when(imageAssetRepository.findImageProjectionsByPublicIdIn(anyCollection()))
                .thenReturn(List.of(projection(
                        assetId, MediaPurpose.RESTAURANT, ImageProcessingStatus.READY,
                        ImageBindingStatus.BOUND, MediaCleanupStatus.ACTIVE,
                        1, SPEC_DIGEST, null, null, null)));
        when(imageRenditionRepository.findActiveImageProjections(
                anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        rendition(assetId, ImageRole.RESTAURANT_CARD, 405, 405),
                        rendition(assetId, ImageRole.RESTAURANT_CARD, 135, 135),
                        rendition(assetId, ImageRole.RESTAURANT_CARD, 270, 270)
                ));
        when(fileStorage.resolveFileUrl(anyString()))
                .thenAnswer(invocation -> "https://cdn.hashi.test/" + invocation.getArgument(0));

        Map<MediaImageRequest, MediaImage> result = mediaPort.findImages(List.of(request));

        assertThat(result).containsOnlyKeys(request);
        MediaImage image = result.get(request);
        assertThat(image.status()).isEqualTo(MediaImageStatus.READY);
        assertThat(image.defaultSource().width()).isEqualTo(270);
        assertThat(image.sourceSets()).singleElement().satisfies(sourceSet ->
                assertThat(sourceSet.candidates())
                        .extracting(MediaImage.Candidate::width)
                        .containsExactly(135, 270, 405));
        verify(imageAssetRepository).findImageProjectionsByPublicIdIn(anyCollection());
        verify(imageRenditionRepository).findActiveImageProjections(
                anyCollection(), anyCollection());
    }

    @Test
    void defaultWidth가_생성되지_않으면_가장_큰_실제_candidate를_기본값으로_쓴다() {
        UUID assetId = UUID.randomUUID();
        MediaImageRequest request =
                new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_CARD);
        when(imageAssetRepository.findImageProjectionsByPublicIdIn(anyCollection()))
                .thenReturn(List.of(projection(
                        assetId, MediaPurpose.RESTAURANT, ImageProcessingStatus.READY,
                        ImageBindingStatus.BOUND, MediaCleanupStatus.ACTIVE,
                        1, SPEC_DIGEST, null, null, null)));
        when(imageRenditionRepository.findActiveImageProjections(
                anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        rendition(assetId, ImageRole.RESTAURANT_CARD, 100, 100),
                        rendition(assetId, ImageRole.RESTAURANT_CARD, 120, 120)
                ));
        when(fileStorage.resolveFileUrl(anyString()))
                .thenReturn("https://cdn.hashi.test/small.webp");

        MediaImage image = mediaPort.findImages(List.of(request)).get(request);

        assertThat(image.defaultSource().width()).isEqualTo(120);
    }

    @Test
    void PROCESSING과_FAILED는_source없이_상태만_반환한다() {
        UUID processingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        MediaImageRequest processing =
                new MediaImageRequest(processingId, MediaImageRole.REVIEW_PREVIEW);
        MediaImageRequest failed =
                new MediaImageRequest(failedId, MediaImageRole.REVIEW_DETAIL);
        when(imageAssetRepository.findImageProjectionsByPublicIdIn(anyCollection()))
                .thenReturn(List.of(
                        projection(
                                processingId, MediaPurpose.REVIEW,
                                ImageProcessingStatus.PROCESSING,
                                ImageBindingStatus.BOUND, MediaCleanupStatus.ACTIVE,
                                null, null, 1, SPEC_DIGEST, null),
                        projection(
                                failedId, MediaPurpose.REVIEW, ImageProcessingStatus.FAILED,
                                ImageBindingStatus.BOUND, MediaCleanupStatus.PURGED,
                                null, null, null, null, 1)
                ));
        when(imageRenditionRepository.findActiveImageProjections(
                anyCollection(), anyCollection())).thenReturn(List.of());

        Map<MediaImageRequest, MediaImage> result =
                mediaPort.findImages(List.of(processing, failed));

        assertThat(result.get(processing).status()).isEqualTo(MediaImageStatus.PROCESSING);
        assertThat(result.get(failed).status()).isEqualTo(MediaImageStatus.FAILED);
        assertThat(result.values()).allSatisfy(image -> {
            assertThat(image.defaultSource()).isNull();
            assertThat(image.sourceSets()).isEmpty();
        });
        verify(fileStorage, never()).resolveFileUrl(anyString());
    }

    @Test
    void spec_digest가_다르거나_asset이_UNBOUND이면_결과에서_제외한다() {
        UUID mismatchId = UUID.randomUUID();
        UUID unboundId = UUID.randomUUID();
        MediaImageRequest mismatch =
                new MediaImageRequest(mismatchId, MediaImageRole.RESTAURANT_CARD);
        MediaImageRequest unbound =
                new MediaImageRequest(unboundId, MediaImageRole.RESTAURANT_CARD);
        when(imageAssetRepository.findImageProjectionsByPublicIdIn(anyCollection()))
                .thenReturn(List.of(
                        projection(
                                mismatchId, MediaPurpose.RESTAURANT,
                                ImageProcessingStatus.READY,
                                ImageBindingStatus.BOUND, MediaCleanupStatus.ACTIVE,
                                1, "0".repeat(64), null, null, null),
                        projection(
                                unboundId, MediaPurpose.RESTAURANT,
                                ImageProcessingStatus.READY,
                                ImageBindingStatus.UNBOUND, MediaCleanupStatus.ACTIVE,
                                1, SPEC_DIGEST, null, null, null)
                ));
        when(imageRenditionRepository.findActiveImageProjections(
                anyCollection(), anyCollection())).thenReturn(List.of());

        assertThat(mediaPort.findImages(List.of(mismatch, unbound))).isEmpty();
    }

    @Test
    void 공개_enum은_내부_manifest_vocabulary와_동일하다() {
        assertThat(MediaAssetPurpose.values())
                .extracting(Enum::name)
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(MediaPurpose.values()).map(Enum::name).toList());
        assertThat(MediaImageRole.values())
                .extracting(Enum::name)
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(ImageRole.values()).map(Enum::name).toList());
    }

    private ImageAsset asset(
            UUID publicId,
            MediaPurpose purpose,
            MediaOwnerType ownerType,
            Long ownerSubjectId,
            ImageProcessingStatus processingStatus,
            ImageBindingStatus bindingStatus,
            MediaCleanupStatus cleanupStatus
    ) {
        ImageAsset asset = mock(ImageAsset.class);
        lenient().when(asset.getPublicId()).thenReturn(publicId);
        lenient().when(asset.getPurpose()).thenReturn(purpose);
        lenient().when(asset.isOwnedBy(ownerType, ownerSubjectId)).thenReturn(true);
        lenient().when(asset.getProcessingStatus()).thenReturn(processingStatus);
        lenient().when(asset.getBindingStatus()).thenReturn(bindingStatus);
        lenient().when(asset.getCleanupStatus()).thenReturn(cleanupStatus);
        return asset;
    }

    private AssetIdentity identity(Long id, UUID publicId) {
        return new TestAssetIdentity(id, publicId);
    }

    private AssetImageProjection projection(
            UUID publicId,
            MediaPurpose purpose,
            ImageProcessingStatus status,
            ImageBindingStatus bindingStatus,
            MediaCleanupStatus cleanupStatus,
            Integer activeSpecVersion,
            String activeSpecDigest,
            Integer targetSpecVersion,
            String targetSpecDigest,
            Integer lastFailureSpecVersion
    ) {
        return new TestAssetImageProjection(
                publicId, purpose, status, bindingStatus, cleanupStatus,
                activeSpecVersion, activeSpecDigest,
                targetSpecVersion, targetSpecDigest, lastFailureSpecVersion);
    }

    private RenditionImageProjection rendition(
            UUID assetId,
            ImageRole role,
            int width,
            int height
    ) {
        return new TestRenditionImageProjection(
                assetId,
                role,
                "image/webp",
                width,
                height,
                "media/renditions/%s/v1/%s/%d.webp".formatted(
                        assetId, role.name().toLowerCase(), width)
        );
    }

    private record TestAssetIdentity(Long id, UUID publicId) implements AssetIdentity {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public UUID getPublicId() {
            return publicId;
        }
    }

    private record TestAssetImageProjection(
            UUID publicId,
            MediaPurpose purpose,
            ImageProcessingStatus processingStatus,
            ImageBindingStatus bindingStatus,
            MediaCleanupStatus cleanupStatus,
            Integer activeSpecVersion,
            String activeSpecDigest,
            Integer targetSpecVersion,
            String targetSpecDigest,
            Integer lastFailureSpecVersion
    ) implements AssetImageProjection {

        @Override
        public UUID getPublicId() { return publicId; }

        @Override
        public MediaPurpose getPurpose() { return purpose; }

        @Override
        public ImageProcessingStatus getProcessingStatus() { return processingStatus; }

        @Override
        public ImageBindingStatus getBindingStatus() { return bindingStatus; }

        @Override
        public MediaCleanupStatus getCleanupStatus() { return cleanupStatus; }

        @Override
        public Integer getActiveSpecVersion() { return activeSpecVersion; }

        @Override
        public String getActiveSpecDigest() { return activeSpecDigest; }

        @Override
        public Integer getTargetSpecVersion() { return targetSpecVersion; }

        @Override
        public String getTargetSpecDigest() { return targetSpecDigest; }

        @Override
        public Integer getLastFailureSpecVersion() { return lastFailureSpecVersion; }
    }

    private record TestRenditionImageProjection(
            UUID assetId,
            ImageRole role,
            String mimeType,
            int width,
            int height,
            String objectKey
    ) implements RenditionImageProjection {

        @Override
        public UUID getAssetId() { return assetId; }

        @Override
        public ImageRole getRole() { return role; }

        @Override
        public String getMimeType() { return mimeType; }

        @Override
        public int getWidth() { return width; }

        @Override
        public int getHeight() { return height; }

        @Override
        public String getObjectKey() { return objectKey; }
    }
}
