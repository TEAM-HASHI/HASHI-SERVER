package org.sopt.hashi.user.service;

import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 닉네임·이메일·연락처 유니크 검사의 단일 구현 — 온보딩 가입, 중복 확인 API, 내 정보 수정이 함께 쓴다.
 * excludedUserId가 있으면 그 회원 자신의 값은 중복으로 보지 않는다(수정 화면과 회원의 중복 확인).
 * 사전 검사라 저장 시점 경합은 막지 못하며, 유니크 제약이 최종 방어한다.
 */
@Component
class ProfileAvailabilityChecker {

    private final UserRepository userRepository;

    ProfileAvailabilityChecker(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    boolean isNicknameAvailable(String nickname, Long excludedUserId) {
        return excludedUserId == null
                ? !userRepository.existsByNickname(nickname)
                : !userRepository.existsByNicknameAndIdNot(nickname, excludedUserId);
    }

    boolean isEmailAvailable(String email, Long excludedUserId) {
        return excludedUserId == null
                ? !userRepository.existsByEmail(email)
                : !userRepository.existsByEmailAndIdNot(email, excludedUserId);
    }

    boolean isPhoneAvailable(String phone, Long excludedUserId) {
        return excludedUserId == null
                ? !userRepository.existsByPhone(phone)
                : !userRepository.existsByPhoneAndIdNot(phone, excludedUserId);
    }

    /** 전달된(null 아닌) 값만 검사해 첫 번째 중복 필드의 에러로 거절한다 — 닉네임·이메일·연락처 순. */
    void requireAvailable(String nickname, String email, String phone, Long excludedUserId) {
        if (nickname != null && !isNicknameAvailable(nickname, excludedUserId)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
        }
        if (email != null && !isEmailAvailable(email, excludedUserId)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        if (phone != null && !isPhoneAvailable(phone, excludedUserId)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_PHONE);
        }
    }
}
