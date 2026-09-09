package org.sopt.hashi.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImage.Candidate;
import org.sopt.hashi.media.MediaImage.Source;
import org.sopt.hashi.media.MediaImage.SourceSet;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.user.AdminUserInfo;
import org.sopt.hashi.user.AdminUserSortType;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserPort userPort;

    @Mock
    private MediaPort mediaPort;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(userPort, mediaPort);
    }

    @Test
    void 회원_목록의_asset_프로필은_한번의_bulk_조회로_투영한다() {
        UUID assetId = UUID.randomUUID();
        AdminUserInfo assetUser = info(
                1L, "asset회원", new ImageReference(assetId, "https://legacy/profile.jpg"));
        AdminUserInfo legacyUser = info(
                2L, "legacy회원", ImageReference.legacy("https://legacy/profile-2.jpg"));
        var page = new PageImpl<>(
                List.of(assetUser, legacyUser), PageRequest.of(0, 20), 2);
        MediaImageRequest request = new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR);
        MediaImage image = readyImage(assetId);
        when(userPort.findPageByAdmin(AdminUserSortType.NICKNAME, null, 0, 20))
                .thenReturn(page);
        when(mediaPort.findImages(List.of(request))).thenReturn(Map.of(request, image));

        var response = adminUserService.getUsers(
                AdminUserSortType.NICKNAME, null, 0, 20);

        assertThat(response.users()).hasSize(2);
        assertThat(response.users().getFirst().profileImageUrl())
                .isEqualTo(image.defaultSource().url());
        assertThat(response.users().getFirst().profileImage()).isEqualTo(image);
        assertThat(response.users().get(1).profileImageUrl())
                .isEqualTo("https://legacy/profile-2.jpg");
        assertThat(response.users().get(1).profileImage()).isNull();
        verify(mediaPort).findImages(List.of(request));
    }

    private AdminUserInfo info(Long id, String nickname, ImageReference reference) {
        return new AdminUserInfo(
                id, nickname, "HASHI", LocalDate.of(1998, 1, 1),
                "01012345678", nickname + "@hashi.test", reference,
                LocalDateTime.of(2026, 8, 31, 1, 0));
    }

    private MediaImage readyImage(UUID assetId) {
        String url = "https://cdn.hashi.test/profile/96.webp";
        return new MediaImage(
                assetId,
                MediaImageRole.PROFILE_AVATAR,
                MediaImageStatus.READY,
                new Source(url, 96, 96, "image/webp"),
                List.of(new SourceSet(
                        "image/webp",
                        List.of(new Candidate(url, 96, 96)))));
    }
}
