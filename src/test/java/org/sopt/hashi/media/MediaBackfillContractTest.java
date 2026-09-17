package org.sopt.hashi.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MediaBackfillContractTest {

    private static final String HASH = "a".repeat(64);

    @ParameterizedTest
    @CsvSource({
            "RESTAURANT_IMAGE, RESTAURANT_IMAGE, IMAGE, RESTAURANT",
            "RESTAURANT_MENU, RESTAURANT_MENU, IMAGE, RESTAURANT_MENU",
            "USER_PROFILE, USER, PROFILE, PROFILE",
            "MAGAZINE_BANNER, MAGAZINE, BANNER, MAGAZINE_BANNER",
            "MAGAZINE_THUMBNAIL, MAGAZINE, THUMBNAIL, MAGAZINE_THUMBNAIL",
            "REVIEW_IMAGE, REVIEW_IMAGE, IMAGE, REVIEW"
    })
    void 소유_슬롯에_따라_identity_marker와_purpose를_고정한다(
            MediaBackfillTarget target, String associationKind, String slot, MediaAssetPurpose purpose) {
        assertThat(target.associationKind()).isEqualTo(associationKind);
        assertThat(target.slot()).isEqualTo(slot);
        assertThat(target.purpose()).isEqualTo(purpose);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 유효하지_않은_association_ID는_거부한다(long associationId) {
        assertThatThrownBy(() -> new MediaBackfillReference(
                MediaBackfillTarget.USER_PROFILE, associationId, "profiles/test.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void 빈_legacy_key는_거부한다(String key) {
        assertThatThrownBy(() -> new MediaBackfillReference(MediaBackfillTarget.USER_PROFILE, 1L, key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad", " ", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void 전달_snapshot의_hash는_소문자_SHA256이어야_한다(String hash) {
        assertThatThrownBy(() -> new MediaBackfillAssetInfo(UUID.randomUUID(), MediaAssetPurpose.PROFILE,
                hash, MediaBackfillAssetInfo.State.PROCESSING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaBackfillInspectionInfo(hash, MediaAssetPurpose.PROFILE, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 조사와_예약의_identity나_purpose가_다르면_결합하지_않는다() {
        MediaBackfillAssetInfo asset = asset();

        assertThatThrownBy(() -> new MediaBackfillInspectionInfo(
                "b".repeat(64), MediaAssetPurpose.PROFILE, Optional.of(asset)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaBackfillInspectionInfo(
                HASH, MediaAssetPurpose.REVIEW, Optional.of(asset))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 진단_문자열에_원시_ID_key와_asset_식별자를_노출하지_않는다() {
        MediaBackfillReference reference =
                new MediaBackfillReference(MediaBackfillTarget.USER_PROFILE, 17L, "profiles/private-name.jpg");
        MediaBackfillAssetInfo asset = asset();
        MediaBackfillInspectionInfo inspection =
                new MediaBackfillInspectionInfo(HASH, MediaAssetPurpose.PROFILE, Optional.of(asset));

        assertThat(reference.toString()).isEqualTo("MediaBackfillReference[redacted]");
        assertThat(asset.toString()).isEqualTo("MediaBackfillAssetInfo[redacted]");
        assertThat(inspection.toString()).isEqualTo("MediaBackfillInspectionInfo[redacted]");
    }

    private MediaBackfillAssetInfo asset() {
        return new MediaBackfillAssetInfo(UUID.randomUUID(), MediaAssetPurpose.PROFILE,
                HASH, MediaBackfillAssetInfo.State.PROCESSING);
    }
}
