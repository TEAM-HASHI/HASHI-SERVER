package org.sopt.hashi.user.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CheckProfileAvailabilityRequest;
import org.sopt.hashi.user.dto.MyInfoResponse;
import org.sopt.hashi.user.dto.ProfileAvailabilityResponse;
import org.sopt.hashi.user.dto.ProfileAvailabilityResponse.FieldAvailability;
import org.sopt.hashi.user.dto.ProfileSummaryResponse;
import org.sopt.hashi.user.dto.UpdateMyInfoRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 프로필 조회·수정. 대상은 항상 {@link CurrentUserProvider}의 현재 사용자다(auth.md §2 — 파라미터 userId 신뢰 금지).
 * asset이 있으면 media 조회 결과를 사용하고, asset이 없는 기존 사진만 {@link FileStorage}로 만든 기존 URL을 응답에 사용한다.
 * 변환 중이거나 실패한 asset은 기존 사진 URL로 우회하지 않는다.
 */
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final FileStorage fileStorage;
    private final CurrentUserProvider currentUserProvider;
    private final MediaPort mediaPort;
    private final ProfileAvailabilityChecker availabilityChecker;

    public UserProfileService(UserRepository userRepository,
                              FileStorage fileStorage,
                              CurrentUserProvider currentUserProvider,
                              MediaPort mediaPort,
                              ProfileAvailabilityChecker availabilityChecker) {
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.currentUserProvider = currentUserProvider;
        this.mediaPort = mediaPort;
        this.availabilityChecker = availabilityChecker;
    }

    /** 내 정보 조회(수정 페이지용) — 온보딩에서 받은 프로필 전체. */
    @Transactional(readOnly = true)
    public MyInfoResponse getMyInfo() {
        User user = currentUser();
        ProjectedImage profileImage = projectProfileImage(user);
        return MyInfoResponse.of(user, profileImage.url(), profileImage.image());
    }

    /** 프로필 요약(헤더·마이페이지용) — 닉네임 + 프로필 사진. */
    @Transactional(readOnly = true)
    public ProfileSummaryResponse getMyProfileSummary() {
        User user = currentUser();
        ProjectedImage profileImage = projectProfileImage(user);
        return ProfileSummaryResponse.of(user, profileImage.url(), profileImage.image());
    }

    /**
     * 닉네임·연락처·이메일 사용 가능 여부 — 전달된 필드만 검사한다. 회원(수정 화면)이면 본인 값을 제외하고,
     * 온보딩 임시 토큰이면 제외할 회원이 없다. 사전 확인일 뿐이라 저장 시점에 다시 검사한다.
     */
    @Transactional(readOnly = true)
    public ProfileAvailabilityResponse checkAvailability(CheckProfileAvailabilityRequest request) {
        Long excludedUserId = currentUserProvider.isAuthenticatedUser()
                ? currentUserProvider.currentUserId()
                : null;
        return new ProfileAvailabilityResponse(
                request.nickname() == null ? null : FieldAvailability.of(
                        availabilityChecker.isNicknameAvailable(request.nickname(), excludedUserId),
                        UserErrorCode.DUPLICATE_NICKNAME),
                request.phone() == null ? null : FieldAvailability.of(
                        availabilityChecker.isPhoneAvailable(request.phone(), excludedUserId),
                        UserErrorCode.DUPLICATE_PHONE),
                request.email() == null ? null : FieldAvailability.of(
                        availabilityChecker.isEmailAvailable(request.email(), excludedUserId),
                        UserErrorCode.DUPLICATE_EMAIL));
    }

    /**
     * 내 정보 부분 수정 — 전달된 필드만 본인 제외로 중복 재검사한 뒤 반영한다. 사진 교체는 새 asset claim과
     * 기존 asset retire를 같은 트랜잭션에서 처리해 일부만 바뀐 상태를 남기지 않는다(MYP-002).
     */
    @Transactional
    public MyInfoResponse updateMyInfo(UpdateMyInfoRequest request) {
        User user = currentUser();
        availabilityChecker.requireAvailable(request.nickname(), request.email(), request.phone(), user.getId());
        user.updateProfile(request.nickname(), request.birthDate(), request.phone(), request.email());
        if (request.profileImageAssetId() != null) {
            replaceProfileImage(user, request.profileImageAssetId());
        }
        flushOrRejectDuplicate();
        ProjectedImage profileImage = projectProfileImage(user);
        return MyInfoResponse.of(user, profileImage.url(), profileImage.image());
    }

    /** 프로필 사진 삭제 — 기본 프로필로 돌린다. 사진이 없으면 할 일이 없으므로 그대로 성공한다(멱등). */
    @Transactional
    public void deleteProfileImage() {
        User user = currentUser();
        retireProfileAsset(user.getProfileImageAssetId());
        user.removeProfileImage();
    }

    /** 토큰은 유효하나 회원이 없으면(탈퇴 직후 잔여 토큰 등) NOT_FOUND — 잔여 토큰 차단(블랙리스트)은 탈퇴 이슈 소관. */
    private User currentUser() {
        return userRepository.findById(currentUserProvider.currentUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
    }

    private ProjectedImage projectProfileImage(User user) {
        ImageReference reference = toProfileImageReference(user);
        MediaImage image = null;
        if (reference != null && reference.assetId() != null) {
            MediaImageRequest request = new MediaImageRequest(
                    reference.assetId(), MediaImageRole.PROFILE_AVATAR);
            Map<MediaImageRequest, MediaImage> projection = mediaPort.findImages(List.of(request));
            image = projection.get(request);
        }
        MediaImageSelection selection = MediaImageSelection.from(reference, image);
        return new ProjectedImage(selection.url(), selection.image());
    }

    private ImageReference toProfileImageReference(User user) {
        if (user.getProfileImageKey() == null && user.getProfileImageAssetId() == null) {
            return null;
        }
        String legacyUrl = user.getProfileImageKey() == null
                ? null
                : fileStorage.resolveFileUrl(user.getProfileImageKey());
        return new ImageReference(user.getProfileImageAssetId(), legacyUrl);
    }

    /** 같은 asset을 다시 보내면 교체할 것이 없다 — claim이 ALREADY_BOUND로 거절하지 않게 건너뛴다. */
    private void replaceProfileImage(User user, UUID newAssetId) {
        UUID currentAssetId = user.getProfileImageAssetId();
        if (newAssetId.equals(currentAssetId)) {
            return;
        }
        mediaPort.reconcileBindings(
                List.of(new MediaAssetUse(newAssetId, MediaAssetPurpose.PROFILE)),
                currentAssetId == null
                        ? List.of()
                        : List.of(new MediaAssetUse(currentAssetId, MediaAssetPurpose.PROFILE)));
        user.replaceProfileImage(newAssetId);
    }

    /** 신규 파이프라인 asset만 media에 연결 해제를 알린다 — 기존 key 사진은 User의 참조만 끊는다. */
    private void retireProfileAsset(UUID assetId) {
        if (assetId != null) {
            mediaPort.reconcileBindings(List.of(), List.of(new MediaAssetUse(assetId, MediaAssetPurpose.PROFILE)));
        }
    }

    /**
     * UPDATE는 커밋 시점에 나가므로 여기서 flush해 사전 검사와 저장 사이의 경합을 잡는다 —
     * 유니크 제약(nickname·email·phone) 위반은 어느 필드인지 특정할 수 없어 일반 충돌로 변환한다(온보딩과 동일).
     */
    private void flushOrRejectDuplicate() {
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(UserErrorCode.DUPLICATE_USER_INFO, e);
        }
    }

    private record ProjectedImage(String url, MediaImage image) {
    }
}
