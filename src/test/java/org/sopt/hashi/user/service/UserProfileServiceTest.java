package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImage.Candidate;
import org.sopt.hashi.media.MediaImage.Source;
import org.sopt.hashi.media.MediaImage.SourceSet;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CheckProfileAvailabilityRequest;
import org.sopt.hashi.user.dto.MyInfoResponse;
import org.sopt.hashi.user.dto.ProfileAvailabilityResponse;
import org.sopt.hashi.user.dto.UpdateMyInfoRequest;
import org.springframework.dao.DataIntegrityViolationException;
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
                userRepository, fileStorage, currentUserProvider, mediaPort,
                new ProfileAvailabilityChecker(userRepository));
        // 온보딩 토큰의 중복 확인은 현재 사용자 id를 읽지 않으므로 공통 stub은 lenient로 둔다
        lenient().when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    @Test
    void legacy_프로필은_기존_URL만_반환하고_media를_조회하지_않는다() {
        User user = user("profiles/legacy.jpg", null);
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));
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
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));
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
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));
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

    @Test
    void 회원의_중복_확인은_본인을_제외하고_전달된_필드만_검사한다() {
        when(currentUserProvider.isAuthenticatedUser()).thenReturn(true);
        when(userRepository.existsByNicknameAndIdNot("도도", 1L)).thenReturn(true);
        when(userRepository.existsByEmailAndIdNot("new@example.com", 1L)).thenReturn(false);

        ProfileAvailabilityResponse response = userProfileService.checkAvailability(
                new CheckProfileAvailabilityRequest("도도", null, "new@example.com"));

        assertThat(response.nickname().available()).isFalse();
        assertThat(response.nickname().message()).isEqualTo("중복된 닉네임입니다.");
        assertThat(response.email().available()).isTrue();
        assertThat(response.email().message()).isNull();
        assertThat(response.phone()).isNull();
        verify(userRepository, never()).existsByNickname(anyString());
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void 온보딩_토큰의_중복_확인은_제외할_회원_없이_전체_회원과_비교한다() {
        when(currentUserProvider.isAuthenticatedUser()).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(true);

        ProfileAvailabilityResponse response = userProfileService.checkAvailability(
                new CheckProfileAvailabilityRequest(null, "01012345678", null));

        assertThat(response.phone().available()).isFalse();
        assertThat(response.phone().message()).isEqualTo("중복된 전화번호입니다.");
        verify(currentUserProvider, never()).currentUserId();
        verify(userRepository, never()).existsByPhoneAndIdNot(anyString(), any());
    }

    @Test
    void 예약어_닉네임은_회원이_확인해도_사용_불가로_응답한다() {
        when(currentUserProvider.isAuthenticatedUser()).thenReturn(true);

        ProfileAvailabilityResponse response = userProfileService.checkAvailability(
                new CheckProfileAvailabilityRequest("한입여행자", null, null));

        assertThat(response.nickname().available()).isFalse();
        verify(userRepository, never()).existsByNicknameAndIdNot(anyString(), any());
    }

    @Test
    void 내_정보_수정은_회원_행을_잠근_뒤_보낸_필드만_바꾼다() {
        User user = user(null, null);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNicknameAndIdNot("도도", 1L)).thenReturn(false);

        MyInfoResponse response = userProfileService.updateMyInfo(
                new UpdateMyInfoRequest("도도", LocalDate.of(2000, 1, 1), null, null, null));

        assertThat(response.nickname()).isEqualTo("도도");
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(user.getPhone()).isEqualTo("01012345678");
        assertThat(user.getEmail()).isEqualTo("hashi@example.com");
        verify(userRepository).flush();
        verify(userRepository, never()).findByIdAndDeletedFalse(any());
        verify(userRepository, never()).existsByEmailAndIdNot(anyString(), any());
        verify(mediaPort, never()).reconcileBindings(any(), any());
    }

    @Test
    void 내_정보_수정은_저장_전_중복이면_필드_코드로_거절하고_flush하지_않는다() {
        User user = user(null, null);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndIdNot("taken@example.com", 1L)).thenReturn(true);

        assertThatThrownBy(() -> userProfileService.updateMyInfo(
                new UpdateMyInfoRequest(null, null, null, "taken@example.com", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.DUPLICATE_EMAIL);
        assertThat(user.getEmail()).isEqualTo("hashi@example.com");
        verify(userRepository, never()).flush();
    }

    @Test
    void 내_정보_수정은_flush_시점의_유니크_위반을_USER_004로_바꾼다() {
        User user = user(null, null);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNicknameAndIdNot("도도", 1L)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("uk_users_nickname")).when(userRepository).flush();

        assertThatThrownBy(() -> userProfileService.updateMyInfo(
                new UpdateMyInfoRequest("도도", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.DUPLICATE_USER_INFO);
    }

    @Test
    void 사진_교체는_새_asset을_claim하고_기존_asset을_retire한다() {
        UUID previous = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        User user = user(null, previous);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        userProfileService.updateMyInfo(new UpdateMyInfoRequest(null, null, null, null, replacement));

        verify(mediaPort).reconcileBindings(
                List.of(new MediaAssetUse(replacement, MediaAssetPurpose.PROFILE)),
                List.of(new MediaAssetUse(previous, MediaAssetPurpose.PROFILE)));
        assertThat(user.getProfileImageAssetId()).isEqualTo(replacement);
        verify(userRepository).flush();
    }

    @Test
    void 사진_삭제는_사진이_없으면_media를_부르지_않고_성공한다() {
        User user = user(null, null);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        userProfileService.deleteProfileImage();

        verifyNoInteractions(mediaPort);
        assertThat(user.getProfileImageAssetId()).isNull();
        assertThat(user.getProfileImageKey()).isNull();
    }

    @Test
    void 사진_삭제는_기존_asset을_retire하고_연결을_끊는다() {
        UUID previous = UUID.randomUUID();
        User user = user(null, previous);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        userProfileService.deleteProfileImage();

        verify(mediaPort).reconcileBindings(
                List.of(), List.of(new MediaAssetUse(previous, MediaAssetPurpose.PROFILE)));
        assertThat(user.getProfileImageAssetId()).isNull();
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
