package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ImageAssetCleanupTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String CHECKSUM = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 12, 0);

    @ParameterizedTest
    @EnumSource(value = ImageProcessingStatus.class, names = {"PENDING_UPLOAD", "EXPIRED", "READY", "FAILED"})
    void 미연결_종료_상태만_정리_작업을_시작한다(ImageProcessingStatus state) {
        ImageAsset asset = assetIn(state, false);
        UUID token = UUID.randomUUID();

        assertThat(asset.canStartPurge()).isTrue();
        asset.beginPurge(token, NOW);

        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGING);
        assertThat(asset.getPurgeToken()).isEqualTo(token);
        assertThat(asset.getPurgeStartedAt()).isEqualTo(NOW);
        assertThat(asset.getPurgeLastAttemptAt()).isEqualTo(NOW);
        assertThat(asset.getObjectsPurgedAt()).isNull();
        assertThat(asset.getProcessingStatus()).isEqualTo(state);
        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
        assertThat(asset.canStartPurge()).isFalse();
        assertThatThrownBy(() -> asset.beginPurge(UUID.randomUUID(), NOW.plusMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(asset.getPurgeToken()).isEqualTo(token);
    }

    @Test
    void 정상_연결과_교체후_보관_이미지는_정리할_수_없다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.READY, false);
        asset.bind();

        assertPurgeRejected(asset);
        asset.retire();
        assertPurgeRejected(asset);
    }

    @Test
    void 최초_변환과_재변환_중인_이미지는_미연결이어도_보호한다() {
        ImageAsset initial = assetIn(ImageProcessingStatus.PROCESSING, false);
        ImageAsset upgrade = assetIn(ImageProcessingStatus.READY, false);
        upgrade.beginUpgradeProcessing(2, "b".repeat(64), UUID.randomUUID(), NOW);

        assertPurgeRejected(initial);
        assertPurgeRejected(upgrade);
        assertThat(upgrade.getActiveSpecVersion()).isEqualTo(1);
        assertThat(upgrade.getTargetSpecVersion()).isEqualTo(2);
        assertThat(initial.getSourceVersionId()).isEqualTo("original-version");
    }

    @Test
    void 정리중에는_업로드_완료와_만료_연결_소유권_인계를_거부한다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.PENDING_UPLOAD, false);
        asset.beginPurge(UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> startProcessing(asset)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(asset::expireUpload).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(asset::bind).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.handoffOwnerAndBind(
                MediaOwnerType.USER, 1L, MediaOwnerType.USER, 2L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(asset.getSourceVersionId()).isNull();
        assertThat(asset.getCurrentJobId()).isNull();
        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
    }

    @Test
    void 재시도는_같은_token과_최초_시각을_보존하고_마지막_시도만_갱신한다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.READY, false);
        UUID token = UUID.randomUUID();
        asset.beginPurge(token, NOW);

        assertThat(asset.resumePurge(UUID.randomUUID(), NOW.plusHours(1), NOW)).isFalse();
        assertThat(asset.resumePurge(token, NOW.plusMinutes(1), NOW.minusSeconds(1))).isFalse();
        assertThat(asset.getPurgeLastAttemptAt()).isEqualTo(NOW);

        assertThat(asset.resumePurge(token, NOW.plusHours(1), NOW)).isTrue();

        assertThat(asset.getPurgeToken()).isEqualTo(token);
        assertThat(asset.getPurgeStartedAt()).isEqualTo(NOW);
        assertThat(asset.getPurgeLastAttemptAt()).isEqualTo(NOW.plusHours(1));
        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGING);
    }

    @Test
    void 시계_역행과_미래의_재시도_기준은_상태를_변경하지_않는다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.READY, false);
        UUID token = UUID.randomUUID();
        asset.beginPurge(token, NOW);

        assertThat(asset.resumePurge(token, NOW.minusSeconds(1), NOW.minusMinutes(1))).isFalse();
        assertThatThrownBy(() -> asset.resumePurge(token, NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(asset.completePurge(token, NOW.minusSeconds(1))).isFalse();
        assertThat(asset.getPurgeLastAttemptAt()).isEqualTo(NOW);
        assertThat(asset.getObjectsPurgedAt()).isNull();
    }

    @Test
    void 파일_정리_완료는_같은_token만_허용하고_중복_완료는_시각을_덮어쓰지_않는다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.READY, false);
        UUID token = UUID.randomUUID();
        assertThat(asset.getRenditions()).hasSize(1);
        asset.beginPurge(token, NOW);

        assertThat(asset.completePurge(UUID.randomUUID(), NOW.plusMinutes(1))).isFalse();
        assertThat(asset.getRenditions()).hasSize(1);
        assertThat(asset.completePurge(token, NOW.plusMinutes(1))).isTrue();

        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGED);
        assertThat(asset.getObjectsPurgedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(asset.getRenditions()).isEmpty();
        assertThat(asset.completePurge(token, NOW.plusHours(1))).isTrue();
        assertThat(asset.getObjectsPurgedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(asset.resumePurge(token, NOW.plusHours(1), NOW)).isFalse();
        assertThat(asset.mustRetainPurgeTombstone()).isFalse();
        assertThatThrownBy(() -> asset.beginUpgradeProcessing(
                2, "b".repeat(64), UUID.randomUUID(), NOW.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 연결된_실패는_정리_중_제거를_막고_완료_후_슬롯과_실패_기록을_유지한다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.PROCESSING, false);
        asset.bind();
        fail(asset);
        UUID token = UUID.randomUUID();
        assertThat(asset.mustRetainPurgeTombstone()).isTrue();

        asset.beginPurge(token, NOW);
        assertThatThrownBy(asset::retire).isInstanceOf(IllegalStateException.class);
        assertThat(asset.completePurge(token, NOW.plusMinutes(1))).isTrue();

        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.FAILED);
        assertThat(asset.getLastFailureSpecVersion()).isEqualTo(1);
        assertThat(asset.getLastFailureCode()).isEqualTo("INVALID_IMAGE_DATA");
        assertThat(asset.mustRetainPurgeTombstone()).isTrue();
        asset.retire();
        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.RETIRED);
        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.PURGED);
    }

    @Test
    void backfill_실패의_identity와_실패_기록은_삭제_완료_뒤에도_보존한다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.FAILED, true);
        UUID token = UUID.randomUUID();
        asset.beginPurge(token, NOW);

        assertThat(asset.completePurge(token, NOW)).isTrue();

        assertThat(asset.mustRetainPurgeTombstone()).isTrue();
        assertThat(asset.getBackfillIdentityHash()).isEqualTo("c".repeat(64));
        assertThat(asset.getLastFailureCode()).isEqualTo("INVALID_IMAGE_DATA");
        assertThat(asset.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
        assertThat(asset.hasCurrentProcessingJob(UUID.randomUUID())).isFalse();
    }

    @Test
    void 일반_미연결_실패와_성공한_backfill은_실패_tombstone을_남기지_않는다() {
        assertThat(assetIn(ImageProcessingStatus.FAILED, false).mustRetainPurgeTombstone()).isFalse();
        assertThat(assetIn(ImageProcessingStatus.READY, true).mustRetainPurgeTombstone()).isFalse();
    }

    @Test
    void 필수_작업_정보가_없으면_정리를_시작하지_않는다() {
        ImageAsset asset = assetIn(ImageProcessingStatus.READY, false);
        assertThatThrownBy(() -> asset.beginPurge(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> asset.beginPurge(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.ACTIVE);
        assertThat(asset.getPurgeToken()).isNull();
    }

    private void assertPurgeRejected(ImageAsset asset) {
        assertThat(asset.canStartPurge()).isFalse();
        assertThatThrownBy(() -> asset.beginPurge(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(asset.getCleanupStatus()).isEqualTo(MediaCleanupStatus.ACTIVE);
        assertThat(asset.getPurgeToken()).isNull();
    }

    private ImageAsset assetIn(ImageProcessingStatus state, boolean backfill) {
        UUID id = UUID.randomUUID();
        String key = "media/originals/%s/original".formatted(id);
        ImageAsset asset = backfill
                ? ImageAsset.createSystemBackfill(id, MediaPurpose.REVIEW, key,
                "image/jpeg", 1024, NOW.minusDays(10), "c".repeat(64))
                : ImageAsset.createDirectUpload(id, MediaPurpose.REVIEW, MediaOwnerType.USER, 1L,
                key, "image/jpeg", 1024, NOW.minusDays(10));
        if (state == ImageProcessingStatus.EXPIRED) {
            asset.expireUpload();
        } else if (state != ImageProcessingStatus.PENDING_UPLOAD) {
            startProcessing(asset);
            if (state == ImageProcessingStatus.FAILED) {
                fail(asset);
            } else if (state == ImageProcessingStatus.READY) {
                UUID job = asset.getCurrentJobId();
                asset.addRendition(job, 1, DIGEST, ImageRole.REVIEW_PREVIEW, ImageFormat.WEBP,
                        135, 135, 100, "media/renditions/%s/v1/review-preview/135.webp".formatted(id));
                asset.completeCurrentProcessing(job, 1, DIGEST, "image/jpeg", 1024, 400, 400, CHECKSUM);
            }
        }
        return asset;
    }

    private void startProcessing(ImageAsset asset) {
        asset.beginInitialProcessing("original-version", "original-etag", 1, DIGEST,
                UUID.randomUUID(), NOW.minusDays(10));
    }

    private void fail(ImageAsset asset) {
        asset.failCurrentProcessing(asset.getCurrentJobId(), 1, DIGEST, "INVALID_IMAGE_DATA");
    }
}
