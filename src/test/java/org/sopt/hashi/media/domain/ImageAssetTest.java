package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageAssetTest {

    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final String NEXT_SPEC_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";
    private static final LocalDateTime PROCESSING_STARTED_AT =
            LocalDateTime.of(2026, 8, 27, 12, 0);

    @Test
    void 직접_업로드는_인증_actor와_PENDING_UPLOAD_상태를_기록한다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);

        assertThat(asset.getCreationOrigin()).isEqualTo(MediaCreationOrigin.DIRECT_UPLOAD);
        assertThat(asset.getCreatorActorType()).isEqualTo(MediaOwnerType.USER);
        assertThat(asset.getCreatorSubjectId()).isEqualTo(1L);
        assertThat(asset.isOwnedBy(MediaOwnerType.USER, 1L)).isTrue();
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.ACTIVE);
    }

    @Test
    void USER와_ADMIN의_숫자_ID가_같아도_소유자는_다르다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);

        assertThat(asset.isOwnedBy(MediaOwnerType.USER, 1L)).isTrue();
        assertThat(asset.isOwnedBy(MediaOwnerType.ADMIN, 1L)).isFalse();
    }

    @Test
    void 원본_식별자와_spec을_고정하고_PROCESSING으로_전이한다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        UUID jobId = UUID.randomUUID();

        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, PROCESSING_STARTED_AT);

        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(asset.getSourceVersionId()).isEqualTo("version-1");
        assertThat(asset.getSourceEtag()).isEqualTo("\"etag-1\"");
        assertThat(asset.getTargetSpecVersion()).isEqualTo(1);
        assertThat(asset.getTargetSpecDigest()).isEqualTo(SPEC_DIGEST);
        assertThat(asset.getCurrentJobId()).isEqualTo(jobId);
        assertThat(asset.getLastIssuedSpecVersion()).isEqualTo(1);
        assertThat(asset.getTargetProcessingStartedAt()).isEqualTo(PROCESSING_STARTED_AT);
        assertThat(asset.getProcessingRecoveryAttempts()).isZero();
    }

    @Test
    void 정체된_현재_job만_재발행_시각과_횟수를_제한적으로_기록한다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, PROCESSING_STARTED_AT);

        assertThat(asset.recordRecoveryRequest(
                jobId,
                PROCESSING_STARTED_AT.plusMinutes(9),
                PROCESSING_STARTED_AT.minusSeconds(1),
                PROCESSING_STARTED_AT.minusMinutes(1),
                2
        )).isFalse();
        assertThat(asset.recordRecoveryRequest(
                jobId,
                PROCESSING_STARTED_AT.plusMinutes(10),
                PROCESSING_STARTED_AT,
                PROCESSING_STARTED_AT.plusMinutes(9),
                2
        )).isTrue();
        assertThat(asset.recordRecoveryRequest(
                jobId,
                PROCESSING_STARTED_AT.plusMinutes(15),
                PROCESSING_STARTED_AT,
                PROCESSING_STARTED_AT.plusMinutes(10),
                2
        )).isTrue();
        assertThat(asset.recordRecoveryRequest(
                jobId,
                PROCESSING_STARTED_AT.plusMinutes(30),
                PROCESSING_STARTED_AT,
                PROCESSING_STARTED_AT.plusMinutes(20),
                2
        )).isFalse();

        assertThat(asset.getProcessingRecoveryAttempts()).isEqualTo(2);
        assertThat(asset.getLastRecoveryRequestedAt())
                .isEqualTo(PROCESSING_STARTED_AT.plusMinutes(15));
    }

    @Test
    void PROCESSING_asset은_같은_완료_전이를_다시_시작할_수_없다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, UUID.randomUUID(),
                PROCESSING_STARTED_AT);

        assertThatThrownBy(() -> asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, UUID.randomUUID(),
                PROCESSING_STARTED_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 현재_job의_성공_결과만_rendition과_READY_spec으로_반영한다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, PROCESSING_STARTED_AT);

        asset.addRendition(
                jobId,
                1,
                SPEC_DIGEST,
                ImageRole.REVIEW_PREVIEW,
                ImageFormat.WEBP,
                135,
                135,
                100L,
                renditionKey(asset, 1, ImageRole.REVIEW_PREVIEW, 135)
        );
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);

        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.READY);
        assertThat(asset.getActiveSpecVersion()).isEqualTo(1);
        assertThat(asset.getActiveSpecDigest()).isEqualTo(SPEC_DIGEST);
        assertThat(asset.getTargetSpecVersion()).isNull();
        assertThat(asset.getCurrentJobId()).isNull();
        assertThat(asset.getTargetProcessingStartedAt()).isNull();
        assertThat(asset.getLastRecoveryRequestedAt()).isNull();
        assertThat(asset.getProcessingRecoveryAttempts()).isZero();
        assertThat(asset.getSourceChecksumSha256()).isEqualTo(SOURCE_CHECKSUM);
        assertThat(asset.getRenditions()).singleElement().satisfies(rendition -> {
            assertThat(rendition.getRole()).isEqualTo(ImageRole.REVIEW_PREVIEW);
            assertThat(rendition.getMimeType()).isEqualTo("image/webp");
        });
    }

    @Test
    void 초기_변환_실패는_FAILED로_전이하고_target을_정리한다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, PROCESSING_STARTED_AT);

        asset.failCurrentProcessing(jobId, 1, SPEC_DIGEST, "INVALID_IMAGE_DATA");

        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.FAILED);
        assertThat(asset.getLastFailureSpecVersion()).isEqualTo(1);
        assertThat(asset.getLastFailureCode()).isEqualTo("INVALID_IMAGE_DATA");
        assertThat(asset.getTargetSpecVersion()).isNull();
        assertThat(asset.getCurrentJobId()).isNull();
    }

    @Test
    void 준비된_이미지의_upgrade_실패는_기존_ACTIVE_spec과_READY를_유지한다() {
        ImageAsset asset = readyAsset();
        UUID upgradeJobId = UUID.randomUUID();
        asset.beginUpgradeProcessing(
                2, NEXT_SPEC_DIGEST, upgradeJobId, PROCESSING_STARTED_AT.plusHours(1));

        asset.failCurrentProcessing(
                upgradeJobId, 2, NEXT_SPEC_DIGEST, "INVALID_IMAGE_DATA");

        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.READY);
        assertThat(asset.getActiveSpecVersion()).isEqualTo(1);
        assertThat(asset.getActiveSpecDigest()).isEqualTo(SPEC_DIGEST);
        assertThat(asset.getLastFailureSpecVersion()).isEqualTo(2);
        assertThat(asset.getCurrentJobId()).isNull();
    }

    @Test
    void 완료된_job의_늦은_실패_결과는_READY를_낮출_수_없다() {
        ImageAsset asset = readyAsset();

        assertThatThrownBy(() -> asset.failCurrentProcessing(
                UUID.randomUUID(), 1, SPEC_DIGEST, "INVALID_IMAGE_DATA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("media processing result is not current");
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.READY);
    }

    @Test
    void 동일한_rendition_식별자를_중복해서_추가할_수_없다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, PROCESSING_STARTED_AT);
        String objectKey = renditionKey(asset, 1, ImageRole.REVIEW_PREVIEW, 135);
        asset.addRendition(
                jobId, 1, SPEC_DIGEST, ImageRole.REVIEW_PREVIEW, ImageFormat.WEBP,
                135, 135, 100L, objectKey);

        assertThatThrownBy(() -> asset.addRendition(
                jobId, 1, SPEC_DIGEST, ImageRole.REVIEW_PREVIEW, ImageFormat.WEBP,
                135, 135, 100L, objectKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rendition identity is duplicated");
    }

    @Test
    void 직접_업로드에_SYSTEM_BACKFILL_actor를_사용할_수_없다() {
        assertThatThrownBy(() -> createDirectUpload(MediaOwnerType.SYSTEM_BACKFILL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 업로드_만료는_PENDING_UPLOAD에서만_가능하다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);

        asset.expireUpload();

        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.EXPIRED);
        assertThatThrownBy(asset::expireUpload).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ACTIVE_UNBOUND_asset은_BIND할_수_있다() {
        ImageAsset asset = readyAsset();

        asset.bind();

        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
        assertThatThrownBy(asset::bind).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void BOUND_asset은_RETIRED로_전이한다() {
        ImageAsset asset = readyAsset();
        asset.bind();

        asset.retire();

        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.RETIRED);
        assertThatThrownBy(asset::retire).isInstanceOf(IllegalStateException.class);
    }

    private ImageAsset createDirectUpload(MediaOwnerType ownerType, Long ownerId) {
        UUID assetId = UUID.randomUUID();
        return ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.REVIEW,
                ownerType,
                ownerId,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
    }

    private ImageAsset readyAsset() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, PROCESSING_STARTED_AT);
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        return asset;
    }

    private String renditionKey(ImageAsset asset, int specVersion, ImageRole role, int width) {
        return "media/renditions/%s/v%d/%s/%d.webp".formatted(
                asset.getPublicId(),
                specVersion,
                role.name().toLowerCase().replace('_', '-'),
                width
        );
    }
}
