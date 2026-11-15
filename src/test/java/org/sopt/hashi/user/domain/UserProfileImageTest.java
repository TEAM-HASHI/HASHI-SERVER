package org.sopt.hashi.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfileImageTest {

    @Test
    void 온보딩_claim_후_빈_프로필에_asset_ID를_연결한다() {
        User user = user(null, null);
        UUID assetId = UUID.randomUUID();

        user.assignOnboardingProfileImage(assetId);

        assertThat(user.getProfileImageKey()).isNull();
        assertThat(user.getProfileImageAssetId()).isEqualTo(assetId);
    }

    @Test
    void 이미_프로필이_있으면_온보딩_연결로_교체하지_않는다() {
        User legacy = user("profiles/legacy.jpg", null);
        UUID originalAssetId = UUID.randomUUID();
        User asset = user(null, originalAssetId);

        assertThatThrownBy(() -> legacy.assignOnboardingProfileImage(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.assignOnboardingProfileImage(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(legacy.getProfileImageKey()).isEqualTo("profiles/legacy.jpg");
        assertThat(asset.getProfileImageAssetId()).isEqualTo(originalAssetId);
    }

    @Test
    void null_asset은_연결하지_않는다() {
        User user = user(null, null);

        assertThatThrownBy(() -> user.assignOnboardingProfileImage(null))
                .isInstanceOf(NullPointerException.class);

        assertThat(user.getProfileImageAssetId()).isNull();
    }

    @Test
    void backfill은_legacy_key와_회원_정보를_그대로_두고_asset만_연결한다() {
        User user = user("profiles/legacy.jpg", null);
        UUID assetId = UUID.randomUUID();

        assertThat(user.attachBackfilledProfileImage("profiles/legacy.jpg", assetId)).isTrue();

        assertThat(user.getProfileImageAssetId()).isEqualTo(assetId);
        assertThat(user.getProfileImageKey()).isEqualTo("profiles/legacy.jpg");
        assertThat(user.getNickname()).isEqualTo("프로필회원");
        assertThat(user.getNameEng()).isEqualTo("HASHI");
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1998, 1, 1));
        assertThat(user.getPhone()).isEqualTo("01000000001");
        assertThat(user.getEmail()).isEqualTo("profile@hashi.test");
        assertThat(user.isDeleted()).isFalse();
    }

    @Test
    void backfill은_변경된_source_기본_프로필_기존_asset과_탈퇴_상태를_덮어쓰지_않는다() {
        User changed = user("profiles/new.jpg", null);
        User defaultProfile = user(null, null);
        UUID originalAssetId = UUID.randomUUID();
        User assetProfile = user(null, originalAssetId);
        User deleted = user("profiles/old.jpg", null);
        ReflectionTestUtils.setField(deleted, "deleted", true);

        for (User candidate : new User[]{changed, defaultProfile, assetProfile, deleted}) {
            assertThat(candidate.attachBackfilledProfileImage("profiles/old.jpg", UUID.randomUUID()))
                    .isFalse();
        }

        assertThat(changed.getProfileImageKey()).isEqualTo("profiles/new.jpg");
        assertThat(changed.getProfileImageAssetId()).isNull();
        assertThat(defaultProfile.getProfileImageAssetId()).isNull();
        assertThat(assetProfile.getProfileImageAssetId()).isEqualTo(originalAssetId);
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getProfileImageAssetId()).isNull();
    }

    @Test
    void backfill은_한번_연결된_asset이나_null_asset으로_교체하지_않는다() {
        User user = user("profiles/legacy.jpg", null);
        UUID assetId = UUID.randomUUID();
        user.attachBackfilledProfileImage("profiles/legacy.jpg", assetId);

        assertThat(user.attachBackfilledProfileImage("profiles/legacy.jpg", UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> user.attachBackfilledProfileImage("profiles/legacy.jpg", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(user.getProfileImageAssetId()).isEqualTo(assetId);
    }

    private User user(String key, UUID assetId) {
        return User.onboard(
                "프로필회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000001", "profile@hashi.test", key, assetId);
    }
}
