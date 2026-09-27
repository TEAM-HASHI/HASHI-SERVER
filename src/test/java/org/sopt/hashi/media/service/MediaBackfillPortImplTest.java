package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillClaim;
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
import org.springframework.test.util.ReflectionTestUtils;

class MediaBackfillPortImplTest {

    private static final String HASH = "a".repeat(64);
    private final ImageAssetRepository repository = mock(ImageAssetRepository.class);
    private final MediaBackfillPreparationService preparation = mock(MediaBackfillPreparationService.class);
    private final MediaBackfillPortImpl port =
            new MediaBackfillPortImpl(repository, new MediaBackfillProperties(true), preparation);

    @Test
    void opt_in하지_않으면_asset을_조회하거나_연결하지_않는다() {
        MediaBackfillPortImpl disabled =
                new MediaBackfillPortImpl(repository, new MediaBackfillProperties(false), preparation);

        assertFailure(() -> disabled.claimReady(List.of(claim(readyAsset()))), MediaErrorCode.PIPELINE_UNAVAILABLE);
        verifyNoInteractions(repository);
    }

    @Test
    void 중복_asset은_조회_전에_거부한다() {
        MediaBackfillClaim claim = claim(readyAsset());

        assertFailure(() -> port.claimReady(List.of(claim, claim)), MediaErrorCode.DUPLICATE_ASSET);
        verifyNoInteractions(repository);
    }

    @Test
    void 내부_ID_오름차순으로_잠그고_모든_asset을_검증한_후_연결한다() {
        ImageAsset first = readyAsset();
        ImageAsset second = readyAsset();
        List<ImageAssetRepository.AssetIdentity> identities = List.of(identity(20L), identity(10L));
        when(repository.findIdentitiesByPublicIdIn(anyCollection()))
                .thenReturn(identities);
        when(repository.findAllByIdInForUpdate(List.of(10L, 20L))).thenReturn(List.of(first, second));

        port.claimReady(List.of(claim(second), claim(first)));

        verify(repository).findAllByIdInForUpdate(List.of(10L, 20L));
        assertThat(first.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
        assertThat(second.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
    }

    @Test
    void 하나라도_조회되지_않으면_연결하지_않는다() {
        ImageAsset asset = readyAsset();
        when(repository.findIdentitiesByPublicIdIn(anyCollection())).thenReturn(List.of());

        assertFailure(() -> port.claimReady(List.of(claim(asset))), MediaErrorCode.ASSET_NOT_FOUND);
        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @ParameterizedTest
    @MethodSource("invalidAssets")
    void 잘못된_asset이_포함되면_앞선_정상_asset도_변경하지_않는다(Consumer<ImageAsset> invalidate) {
        ImageAsset valid = readyAsset();
        ImageAsset invalid = readyAsset();
        invalidate.accept(invalid);
        List<ImageAssetRepository.AssetIdentity> identities = List.of(identity(1L), identity(2L));
        when(repository.findIdentitiesByPublicIdIn(anyCollection()))
                .thenReturn(identities);
        when(repository.findAllByIdInForUpdate(List.of(1L, 2L))).thenReturn(List.of(valid, invalid));

        assertThatThrownBy(() -> port.claimReady(List.of(claim(valid), claim(invalid))))
                .isInstanceOf(BusinessException.class);
        assertThat(valid.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad", " ", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void claim은_소문자_SHA256_hash만_받는다(String hash) {
        assertThatThrownBy(() -> new MediaBackfillClaim(UUID.randomUUID(), MediaAssetPurpose.PROFILE, hash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claim의_진단_문자열은_식별자를_노출하지_않는다() {
        assertThat(claim(readyAsset()).toString()).isEqualTo("MediaBackfillClaim[redacted]");
    }

    private static Stream<Consumer<ImageAsset>> invalidAssets() {
        return Stream.of(
                asset -> ReflectionTestUtils.setField(asset, "creationOrigin", MediaCreationOrigin.DIRECT_UPLOAD),
                asset -> ReflectionTestUtils.setField(asset, "ownerActorType", MediaOwnerType.ADMIN),
                asset -> ReflectionTestUtils.setField(asset, "ownerSubjectId", 1L),
                asset -> ReflectionTestUtils.setField(asset, "creatorActorType", MediaOwnerType.ADMIN),
                asset -> ReflectionTestUtils.setField(asset, "creatorSubjectId", 1L),
                asset -> ReflectionTestUtils.setField(asset, "purpose", MediaPurpose.REVIEW),
                asset -> ReflectionTestUtils.setField(asset, "backfillIdentityHash", "b".repeat(64)),
                asset -> ReflectionTestUtils.setField(asset, "processingStatus", ImageProcessingStatus.PROCESSING),
                asset -> ReflectionTestUtils.setField(asset, "processingStatus", ImageProcessingStatus.FAILED),
                asset -> ReflectionTestUtils.setField(asset, "bindingStatus", ImageBindingStatus.BOUND),
                asset -> ReflectionTestUtils.setField(asset, "bindingStatus", ImageBindingStatus.RETIRED),
                asset -> ReflectionTestUtils.setField(asset, "cleanupStatus", MediaCleanupStatus.PURGING),
                asset -> ReflectionTestUtils.setField(asset, "cleanupStatus", MediaCleanupStatus.PURGED));
    }

    private ImageAsset readyAsset() {
        UUID id = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createSystemBackfill(id, MediaPurpose.PROFILE,
                "media/originals/" + id + "/original", "image/jpeg", 1024,
                LocalDateTime.of(2026, 8, 31, 12, 0), HASH);
        ReflectionTestUtils.setField(asset, "processingStatus", ImageProcessingStatus.READY);
        return asset;
    }

    private MediaBackfillClaim claim(ImageAsset asset) {
        return new MediaBackfillClaim(asset.getPublicId(), MediaAssetPurpose.PROFILE, HASH);
    }

    private ImageAssetRepository.AssetIdentity identity(long id) {
        ImageAssetRepository.AssetIdentity identity = mock(ImageAssetRepository.AssetIdentity.class);
        when(identity.getId()).thenReturn(id);
        return identity;
    }

    private void assertFailure(Runnable action, MediaErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", code);
    }
}
