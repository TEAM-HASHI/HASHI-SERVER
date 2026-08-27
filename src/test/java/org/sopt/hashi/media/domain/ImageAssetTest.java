package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageAssetTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";

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

        asset.beginInitialProcessing("version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId);

        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(asset.getSourceVersionId()).isEqualTo("version-1");
        assertThat(asset.getSourceEtag()).isEqualTo("\"etag-1\"");
        assertThat(asset.getTargetSpecVersion()).isEqualTo(1);
        assertThat(asset.getTargetSpecDigest()).isEqualTo(SPEC_DIGEST);
        assertThat(asset.getCurrentJobId()).isEqualTo(jobId);
        assertThat(asset.getLastIssuedSpecVersion()).isEqualTo(1);
    }

    @Test
    void PROCESSING_asset은_같은_완료_전이를_다시_시작할_수_없다() {
        ImageAsset asset = createDirectUpload(MediaOwnerType.USER, 1L);
        asset.beginInitialProcessing("version-1", "\"etag-1\"", 1, SPEC_DIGEST, UUID.randomUUID());

        assertThatThrownBy(() -> asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
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
}
