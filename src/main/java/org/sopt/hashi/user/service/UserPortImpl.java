package org.sopt.hashi.user.service;

import java.util.Optional;
import org.sopt.hashi.user.UserInfo;
import org.sopt.hashi.user.UserPort;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.stereotype.Component;

/**
 * user 공개 포트 구현 — 회원 요약을 Repository에서 조회해 {@link UserInfo}로 변환한다.
 */
@Component
class UserPortImpl implements UserPort {

    private final UserRepository userRepository;

    UserPortImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<UserInfo> findById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> new UserInfo(
                        user.getId(),
                        user.getNickname(),
                        user.getNameEng(),
                        user.getBirthDate(),
                        user.getPhone(),
                        user.getEmail()));
    }
}
