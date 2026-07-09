package org.sopt.hashi.user.service;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.MyInfoResponse;
import org.sopt.hashi.user.dto.ProfileSummaryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 프로필 조회. 대상은 항상 {@link CurrentUserProvider}의 현재 사용자다(auth.md §2 — 파라미터 userId 신뢰 금지).
 * 프로필 사진은 저장된 S3 key를 {@link FileStorage}로 조회 URL로 변환해 내린다(coding-style §4-2).
 */
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final FileStorage fileStorage;
    private final CurrentUserProvider currentUserProvider;

    public UserProfileService(UserRepository userRepository,
                              FileStorage fileStorage,
                              CurrentUserProvider currentUserProvider) {
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.currentUserProvider = currentUserProvider;
    }

    /** 내 정보 조회(수정 페이지용) — 온보딩에서 받은 프로필 전체. */
    @Transactional(readOnly = true)
    public MyInfoResponse getMyInfo() {
        User user = currentUser();
        return MyInfoResponse.of(user, fileStorage.resolveFileUrl(user.getProfileImageKey()));
    }

    /** 프로필 요약(헤더·마이페이지용) — 닉네임 + 프로필 사진. */
    @Transactional(readOnly = true)
    public ProfileSummaryResponse getMyProfileSummary() {
        User user = currentUser();
        return ProfileSummaryResponse.of(user, fileStorage.resolveFileUrl(user.getProfileImageKey()));
    }

    /** 토큰은 유효하나 회원이 없으면(탈퇴 직후 잔여 토큰 등) NOT_FOUND — 잔여 토큰 차단(블랙리스트)은 탈퇴 이슈 소관. */
    private User currentUser() {
        return userRepository.findById(currentUserProvider.currentUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
    }
}
