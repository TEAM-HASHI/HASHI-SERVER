package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties.Mode;
import org.sopt.hashi.media.internal.recovery.MediaRecoveryProperties;
import org.springframework.test.util.ReflectionTestUtils;

class MediaCleanupEligibilityPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 21, 0);
    private static final String DIGEST = "a".repeat(64);
    private final MediaRecoveryProperties retention = new MediaRecoveryProperties(false, null, null, 0,
            null, null, 0, 0, 0, null, null, null, null, null);
    private final MediaCleanupProperties cleanup = new MediaCleanupProperties(true, Mode.DELETE,
            Duration.ofMinutes(30), null, null, 0, 0, 0, 0, null, null);
    private final MediaCleanupEligibilityPolicy policy = new MediaCleanupEligibilityPolicy(retention, cleanup);

    @ParameterizedTest
    @MethodSource("retentionCases")
    void 상태와_생성경로의_보존기간을_채워야_정리한다(ImageProcessingStatus state,
                                                 MediaCreationOrigin origin, int days) {
        ImageAsset asset = asset(state, origin, NOW.minusDays(days));

        assertThat(policy.isEligible(asset, NOW.minusNanos(1))).isFalse();
        assertThat(policy.isEligible(asset, NOW)).isTrue();
        assertThat(policy.retentionFor(state, origin, ImageBindingStatus.UNBOUND)).isEqualTo(Duration.ofDays(days));
        assertThat(asset.getPurgeToken()).isNull();
    }

    @Test
    void 보존기간을_채워도_업로드_URL_만료와_유예시간까지_기다린다() {
        for (ImageProcessingStatus state : ImageProcessingStatus.values()) {
            if (state == ImageProcessingStatus.PROCESSING) {
                continue;
            }
            ImageAsset asset = asset(state, MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(10));
            ReflectionTestUtils.setField(asset, "uploadExpiresAt", NOW.minusMinutes(30));

            assertThat(policy.isEligible(asset, NOW.minusNanos(1))).isFalse();
            assertThat(policy.isEligible(asset, NOW)).isTrue();
            ReflectionTestUtils.setField(asset, "uploadExpiresAt", NOW.plusMinutes(1));
            assertThat(policy.isEligible(asset, NOW)).isFalse();
        }
    }

    @Test
    void 연결된_실패는_정리하되_정상_연결과_RETIRED는_정리하지_않는다() {
        ImageAsset failed = asset(ImageProcessingStatus.FAILED, MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(7));
        failed.bind();
        ImageAsset ready = asset(ImageProcessingStatus.READY, MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(30));
        ready.bind();

        assertThat(policy.isEligible(failed, NOW)).isTrue();
        assertThat(policy.isEligible(ready, NOW)).isFalse();
        ready.retire();
        assertThat(policy.isEligible(ready, NOW)).isFalse();
        failed.retire();
        assertThat(policy.isEligible(failed, NOW)).isFalse();
    }

    @Test
    void 최초_변환과_규격_재변환_중인_이미지는_오래되어도_보호한다() {
        ImageAsset initial = asset(ImageProcessingStatus.PROCESSING,
                MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(30));
        ImageAsset upgrade = asset(ImageProcessingStatus.READY,
                MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(30));
        upgrade.beginUpgradeProcessing(2, "b".repeat(64), UUID.randomUUID(), NOW.minusDays(20));

        assertThat(policy.isEligible(initial, NOW)).isFalse();
        assertThat(policy.isEligible(upgrade, NOW)).isFalse();
    }

    @Test
    void 정리중이거나_완료된_asset은_새_정리를_시작하지_않는다() {
        ImageAsset asset = asset(ImageProcessingStatus.READY, MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(30));
        UUID token = UUID.randomUUID();
        asset.beginPurge(token, NOW);

        assertThat(policy.isEligible(asset, NOW)).isFalse();
        asset.completePurge(token, NOW);
        assertThat(policy.isEligible(asset, NOW)).isFalse();
    }

    @Test
    void canonical_원본_경로가_아니거나_보존_기준시각이_없으면_삭제하지_않는다() {
        ImageAsset asset = asset(ImageProcessingStatus.READY, MediaCreationOrigin.DIRECT_UPLOAD, NOW.minusDays(30));
        ReflectionTestUtils.setField(asset, "originalObjectKey", "legacy/photo.jpg");

        assertThat(policy.hasCanonicalOriginal(asset)).isFalse();
        assertThat(policy.isEligible(asset, NOW)).isFalse();
        ReflectionTestUtils.setField(asset, "originalObjectKey",
                "media/originals/" + asset.getPublicId() + "/original");
        ReflectionTestUtils.setField(asset, "updatedAt", null);
        assertThat(policy.isEligible(asset, NOW)).isFalse();
    }

    @Test
    void 운영_보존기간이_다르면_backfill_UNBOUND의_더_긴_기준을_적용한다() {
        MediaRecoveryProperties custom = new MediaRecoveryProperties(false, null, null, 0,
                null, null, 0, 0, 0, null, Duration.ofDays(10), Duration.ofDays(2),
                Duration.ofDays(7), Duration.ofDays(14));
        MediaCleanupEligibilityPolicy configured = new MediaCleanupEligibilityPolicy(custom, cleanup);

        assertThat(configured.retentionFor(ImageProcessingStatus.PENDING_UPLOAD,
                MediaCreationOrigin.SYSTEM_BACKFILL, ImageBindingStatus.UNBOUND)).isEqualTo(Duration.ofDays(10));
        assertThat(configured.retentionFor(ImageProcessingStatus.READY,
                MediaCreationOrigin.SYSTEM_BACKFILL, ImageBindingStatus.UNBOUND)).isEqualTo(Duration.ofDays(7));
        assertThat(configured.retentionFor(ImageProcessingStatus.FAILED,
                MediaCreationOrigin.SYSTEM_BACKFILL, ImageBindingStatus.UNBOUND)).isEqualTo(Duration.ofDays(14));
    }

    private static Stream<Arguments> retentionCases() {
        return Stream.of(MediaCreationOrigin.values()).flatMap(origin -> Stream.of(
                ImageProcessingStatus.PENDING_UPLOAD, ImageProcessingStatus.EXPIRED,
                ImageProcessingStatus.READY, ImageProcessingStatus.FAILED).map(state ->
                Arguments.of(state, origin,
                        origin == MediaCreationOrigin.SYSTEM_BACKFILL
                                || state == ImageProcessingStatus.FAILED ? 7 : 1)));
    }

    private ImageAsset asset(ImageProcessingStatus state, MediaCreationOrigin origin, LocalDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        String key = "media/originals/" + id + "/original";
        ImageAsset asset = origin == MediaCreationOrigin.DIRECT_UPLOAD
                ? ImageAsset.createDirectUpload(id, MediaPurpose.REVIEW, MediaOwnerType.USER, 1L,
                key, "image/jpeg", 1024, NOW.minusDays(40))
                : ImageAsset.createSystemBackfill(id, MediaPurpose.REVIEW, key, "image/jpeg", 1024,
                NOW.minusDays(40), "c".repeat(64));
        if (state == ImageProcessingStatus.EXPIRED) {
            asset.expireUpload();
        } else if (state != ImageProcessingStatus.PENDING_UPLOAD) {
            UUID job = UUID.randomUUID();
            asset.beginInitialProcessing("source-v1", "etag", 1, DIGEST, job, NOW.minusDays(30));
            if (state == ImageProcessingStatus.FAILED) {
                asset.failCurrentProcessing(job, 1, DIGEST, "INVALID_IMAGE_DATA");
            } else if (state == ImageProcessingStatus.READY) {
                asset.completeCurrentProcessing(job, 1, DIGEST, "image/jpeg", 1024, 400, 400,
                        "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
            }
        }
        ReflectionTestUtils.setField(asset, "updatedAt", updatedAt);
        return asset;
    }
}
