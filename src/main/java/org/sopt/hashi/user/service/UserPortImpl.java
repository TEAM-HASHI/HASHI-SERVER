package org.sopt.hashi.user.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserInfo;
import org.sopt.hashi.user.UserPort;
import org.sopt.hashi.user.UserProfileInfo;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * user 공개 포트 구현 — 회원 정보를 Repository에서 조회해 공개 계약으로 변환한다.
 */
@Component
@Transactional(readOnly = true)
class UserPortImpl implements UserPort {

    private final UserRepository userRepository;
    private final FileStorage fileStorage;

    UserPortImpl(UserRepository userRepository, FileStorage fileStorage) {
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<UserInfo> findById(Long userId) {
        return userRepository.findById(userId)
                .map(this::toUserInfo);
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

    private UserInfo toUserInfo(User user) {
        return new UserInfo(
                user.getId(),
                user.getNickname(),
                user.getNameEng(),
                user.getBirthDate(),
                user.getPhone(),
                user.getEmail());
    }

    private UserProfileInfo toProfileInfo(User user) {
        return new UserProfileInfo(
                user.getId(),
                user.getNickname(),
                resolveProfileImageUrl(user.getProfileImageKey()));
    }

    private String resolveProfileImageUrl(String profileImageKey) {
        if (profileImageKey == null || profileImageKey.isBlank()) {
            return null;
        }
        return fileStorage.resolveFileUrl(profileImageKey);
    }
}
