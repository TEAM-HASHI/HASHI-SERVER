package org.sopt.hashi.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

    private User user(String key, UUID assetId) {
        return User.onboard(
                "프로필회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000001", "profile@hashi.test", key, assetId);
    }
}
