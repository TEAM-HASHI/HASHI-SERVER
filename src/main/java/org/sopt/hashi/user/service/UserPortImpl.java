package org.sopt.hashi.user.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.AdminUserInfo;
import org.sopt.hashi.user.AdminUserSortType;
import org.sopt.hashi.user.UserInfo;
import org.sopt.hashi.user.UserPort;
import org.sopt.hashi.user.UserProfileInfo;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * user 공개 포트 구현 — 회원 정보를 Repository에서 조회해 공개 계약으로 변환한다.
 * 탈퇴 회원은 프로필 요약(findProfiles)에만 익명 닉네임으로 포함하고 나머지 조회에서는 제외한다.
 */
@Component
@Transactional(readOnly = true)
class UserPortImpl implements UserPort {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final FileStorage fileStorage;

    UserPortImpl(UserRepository userRepository, FileStorage fileStorage) {
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<UserInfo> findById(Long userId) {
        return userRepository.findByIdAndDeletedFalse(userId)
                .map(this::toUserInfo);
    }

    @Override
    public boolean existsById(Long userId) {
        return userRepository.existsByIdAndDeletedFalse(userId);
    }

    @Override
    public List<UserProfileInfo> findProfiles(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, UserProfileInfo> profilesById = userRepository.findAllById(ids).stream()
                .map(this::toProfileInfo)
                .collect(Collectors.toMap(UserProfileInfo::id, Function.identity()));

        return ids.stream()
                .map(profilesById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Page<AdminUserInfo> findPageByAdmin(AdminUserSortType sortType, String nicknameKeyword,
                                               int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size), sortType.toSort());
        Page<User> users = hasKeyword(nicknameKeyword)
                ? userRepository.findByNicknameContainingAndDeletedFalse(nicknameKeyword.trim(), pageable)
                : userRepository.findByDeletedFalse(pageable);
        return users.map(this::toAdminInfo);
    }

    private UserInfo toUserInfo(User user) {
        return new UserInfo(
                user.getId(),
                user.getNickname(),
                user.getNameEng(),
                user.getBirthDate(),
                user.getPhone(),
                user.getEmail());
    }

    /** 탈퇴 회원은 익명 닉네임과 기본 프로필(이미지 없음)로 내린다 — 리뷰 작성자 표시 정책(REVIEW_POLICY). */
    private UserProfileInfo toProfileInfo(User user) {
        if (user.isDeleted()) {
            return new UserProfileInfo(user.getId(), user.getAnonymousNickname(), null);
        }
        return new UserProfileInfo(
                user.getId(),
                user.getNickname(),
                toProfileImageReference(user));
    }

    private AdminUserInfo toAdminInfo(User user) {
        return new AdminUserInfo(
                user.getId(),
                user.getNickname(),
                user.getNameEng(),
                user.getBirthDate(),
                user.getPhone(),
                user.getEmail(),
                toProfileImageReference(user),
                user.getCreatedAt());
    }

    private boolean hasKeyword(String keyword) {
        return keyword != null && !keyword.isBlank();
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private ImageReference toProfileImageReference(User user) {
        String profileImageKey = user.getProfileImageKey();
        if (profileImageKey == null && user.getProfileImageAssetId() == null) {
            return null;
        }
        String legacyUrl = profileImageKey == null
                ? null
                : fileStorage.resolveFileUrl(profileImageKey);
        return new ImageReference(user.getProfileImageAssetId(), legacyUrl);
    }
}
