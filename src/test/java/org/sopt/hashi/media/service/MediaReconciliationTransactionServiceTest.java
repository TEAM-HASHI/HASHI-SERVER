package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageRenditionRepository;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.TargetProcessingStatus;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectLocation;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersion;

class MediaReconciliationTransactionServiceTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final Instant OLD = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-08-08T00:00:00Z");

    private final ImageAssetRepository assets = mock(ImageAssetRepository.class);
    private final ImageRenditionRepository renditions = mock(ImageRenditionRepository.class);
    private final MediaReconciliationTransactionService service =
            new MediaReconciliationTransactionService(assets, renditions);
    private ImageAsset asset;

    @BeforeEach
    void setUp() {
        asset = mock(ImageAsset.class);
        when(asset.getId()).thenReturn(11L);
        when(asset.getPublicId()).thenReturn(ASSET_ID);
        when(asset.getCleanupStatus()).thenReturn(MediaCleanupStatus.ACTIVE);
        when(assets.findByPublicIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));
    }

    @Test
    void DB에_없는_정상_경로는_7일_뒤_삭제_후보다() {
        when(assets.findByPublicIdForUpdate(ASSET_ID)).thenReturn(Optional.empty());

        assertThat(service.assess(original("other-version", OLD), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.DELETE);
    }

    @Test
    void 유예_기간이_지나지_않으면_DB도_조회하지_않는다() {
        assertThat(service.assess(original("other-version", CUTOFF.plusSeconds(1)), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.PROTECT);

        verify(assets, never()).findByPublicIdForUpdate(ASSET_ID);
    }

    @Test
    void 현재_version이_아니어도_DB가_고정한_원본은_보존한다() {
        when(asset.getOriginalObjectKey()).thenReturn(originalKey());
        when(asset.getSourceVersionId()).thenReturn("canonical-version");

        assertThat(service.assess(original("canonical-version", OLD), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
        assertThat(service.assess(original("noncanonical-version", OLD), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.DELETE);
    }

    @Test
    void sourceVersion이_아직_없는_asset의_원본은_전체_cleanup에_맡긴다() {
        when(asset.getOriginalObjectKey()).thenReturn(originalKey());
        when(asset.getSourceVersionId()).thenReturn(null);

        assertThat(service.assess(original("observed-version", OLD), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
    }

    @Test
    void active_target_과거_manifest_spec은_개별_manifest가_없는_파일도_보존한다() {
        when(asset.getActiveSpecVersion()).thenReturn(1);
        when(asset.getTargetSpecVersion()).thenReturn(3);
        when(asset.getTargetProcessingStatus()).thenReturn(TargetProcessingStatus.PROCESSING);
        when(asset.getLastIssuedSpecVersion()).thenReturn(3);
        when(renditions.existsByImageAssetIdAndSpecVersion(11L, 2)).thenReturn(true);

        assertThat(service.assess(rendition(1, "review-preview", 135, "v1"), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
        assertThat(service.assess(rendition(3, "review-preview", 135, "v3"), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
        assertThat(service.assess(rendition(2, "review-detail", 1080, "v2"), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.PROTECT);
    }

    @Test
    void terminal_실패한_manifest_없는_spec만_삭제한다() {
        when(asset.getActiveSpecVersion()).thenReturn(1);
        when(asset.getLastIssuedSpecVersion()).thenReturn(2);
        when(renditions.existsByImageAssetIdAndSpecVersion(11L, 2)).thenReturn(false);

        assertThat(service.assess(rendition(2, "review-preview", 135, "failed"), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.DELETE);
    }

    @Test
    void 발급_기록보다_미래인_spec과_불명확한_경로는_삭제하지_않는다() {
        when(asset.getLastIssuedSpecVersion()).thenReturn(2);

        assertThat(service.assess(rendition(3, "review-preview", 135, "future"), CUTOFF))
                .isEqualTo(MediaReconciliationDecision.UNKNOWN);
        MediaObjectVersion malformed = new MediaObjectVersion(MediaObjectLocation.RENDITION,
                "media/renditions/" + ASSET_ID + "/v2/unknown-role/135.webp", "v", OLD);
        assertThat(service.assess(malformed, CUTOFF)).isEqualTo(MediaReconciliationDecision.UNKNOWN);
    }

    @Test
    void PURGING은_기존_cleanup이_담당하고_PURGED_뒤_늦은_파일은_다시_정리한다() {
        when(asset.getCleanupStatus()).thenReturn(MediaCleanupStatus.PURGING, MediaCleanupStatus.PURGED);
        MediaObjectVersion late = rendition(2, "review-preview", 135, "late");

        assertThat(service.assess(late, CUTOFF)).isEqualTo(MediaReconciliationDecision.PROTECT);
        assertThat(service.assess(late, CUTOFF)).isEqualTo(MediaReconciliationDecision.DELETE);
    }

    private MediaObjectVersion original(String version, Instant lastModified) {
        return new MediaObjectVersion(MediaObjectLocation.ORIGINAL, originalKey(), version, lastModified);
    }

    private MediaObjectVersion rendition(int spec, String role, int width, String version) {
        return new MediaObjectVersion(MediaObjectLocation.RENDITION,
                "media/renditions/%s/v%d/%s/%d.webp".formatted(ASSET_ID, spec, role, width), version, OLD);
    }

    private String originalKey() {
        return "media/originals/%s/original".formatted(ASSET_ID);
    }
}
