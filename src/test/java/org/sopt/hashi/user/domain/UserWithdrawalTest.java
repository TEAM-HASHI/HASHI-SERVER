package org.sopt.hashi.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserWithdrawalTest {

    @Test
    void 탈퇴하면_삭제_표시와_함께_유니크_정보를_자리값으로_바꾸고_영문_이름을_지우고_익명_닉네임을_배정한다() {
        User user = user(42L, "profiles/legacy.jpg", null);

        user.withdraw();

        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getNickname()).isEqualTo("탈퇴회원#42");
        assertThat(user.getEmail()).isEqualTo("withdrawn+42@hashi.invalid");
        assertThat(user.getPhone()).isEqualTo("withdrawn-42");
        assertThat(user.getAnonymousNickname()).isIn(AnonymousNickname.CANDIDATES);
        assertThat(user.getProfileImageKey()).isNull();
        assertThat(user.getProfileImageAssetId()).isNull();
        assertThat(user.getNameEng()).isNull();
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1998, 1, 1));
    }

    @Test
    void 탈퇴는_asset_프로필_연결도_끊는다() {
        User user = user(42L, null, UUID.randomUUID());

        user.withdraw();

        assertThat(user.getProfileImageAssetId()).isNull();
    }

    @Test
    void 이미_탈퇴한_회원은_다시_탈퇴할_수_없다() {
        User user = user(42L, null, null);
        user.withdraw();

        assertThatThrownBy(user::withdraw).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 같은_회원은_항상_같은_익명_닉네임을_받고_후보는_20개로_겹치지_않는다() {
        assertThat(AnonymousNickname.assignFor(42L)).isEqualTo(AnonymousNickname.assignFor(42L));
        assertThat(AnonymousNickname.CANDIDATES).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void 탈퇴_자리값_접두어와_익명_닉네임_후보는_예약어다() {
        assertThat(User.isReservedNickname("탈퇴회원#1")).isTrue();
        assertThat(User.isReservedNickname("한입여행자")).isTrue();
        assertThat(User.isReservedNickname("김하람")).isFalse();
        assertThat(User.isReservedNickname("한입여행자2")).isFalse();
    }

    @Test
    void 탈퇴_자리값_이메일_도메인은_대소문자와_무관하게_예약어다() {
        assertThat(User.isReservedEmail("withdrawn+7@hashi.invalid")).isTrue();
        assertThat(User.isReservedEmail("anyone@HASHI.INVALID")).isTrue();
        assertThat(User.isReservedEmail("hashi@example.com")).isFalse();
    }

    private User user(Long id, String key, UUID assetId) {
        User user = User.onboard(
                "하시", "HASHI", LocalDate.of(1998, 1, 1),
                "01012345678", "hashi@example.com", key, assetId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
