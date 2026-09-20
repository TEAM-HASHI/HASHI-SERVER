package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImage.Candidate;
import org.sopt.hashi.media.MediaImage.Source;
import org.sopt.hashi.media.MediaImage.SourceSet;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorage fileStorage;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private MediaPort mediaPort;

    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileService(
                userRepository, fileStorage, currentUserProvider, mediaPort);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    @Test
    void legacy_프로필은_기존_URL만_반환하고_media를_조회하지_않는다() {
        User user = user("profiles/legacy.jpg", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(fileStorage.resolveFileUrl("profiles/legacy.jpg"))
                .thenReturn("https://cdn.hashi.test/profiles/legacy.jpg");

        var response = userProfileService.getMyInfo();

        assertThat(response.profileImageUrl())
                .isEqualTo("https://cdn.hashi.test/profiles/legacy.jpg");
        assertThat(response.profileImage()).isNull();
        verify(mediaPort, never()).findImages(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void READY_asset_프로필은_기본_URL과_반응형_이미지를_함께_반환한다() {
        UUID assetId = UUID.randomUUID();
        User user = user(null, assetId);
        MediaImage image = readyImage(assetId);
        MediaImageRequest request = new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(mediaPort.findImages(List.of(request))).thenReturn(Map.of(request, image));

        var response = userProfileService.getMyProfileSummary();

        assertThat(response.profileImageUrl()).isEqualTo(image.defaultSource().url());
        assertThat(response.profileImage()).isEqualTo(image);
        verify(fileStorage, never()).resolveFileUrl(anyString());
    }

    @Test
    void asset이_있으면_FAILED나_lookup_mismatch에_legacy_URL로_우회하지_않는다() {
        UUID assetId = UUID.randomUUID();
        User user = user("profiles/backfill.jpg", assetId);
        MediaImageRequest request = new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR);
        MediaImage failed = new MediaImage(
                assetId, MediaImageRole.PROFILE_AVATAR, MediaImageStatus.FAILED,
                null, List.of());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(fileStorage.resolveFileUrl("profiles/backfill.jpg"))
                .thenReturn("https://cdn.hashi.test/profiles/backfill.jpg");
        when(mediaPort.findImages(List.of(request))).thenReturn(Map.of(request, failed));

        var failedResponse = userProfileService.getMyInfo();
        when(mediaPort.findImages(List.of(request))).thenReturn(Map.of());
        var mismatchResponse = userProfileService.getMyInfo();

        assertThat(failedResponse.profileImageUrl()).isNull();
        assertThat(failedResponse.profileImage()).isEqualTo(failed);
        assertThat(mismatchResponse.profileImageUrl()).isNull();
        assertThat(mismatchResponse.profileImage()).isNull();
    }

    private User user(String key, UUID assetId) {
        User user = User.onboard(
                "하시", "HASHI", LocalDate.of(1998, 1, 1),
                "01012345678", "hashi@example.com", key);
        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(user, "profileImageAssetId", assetId);
        return user;
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
