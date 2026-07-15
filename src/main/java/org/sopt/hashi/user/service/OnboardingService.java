package org.sopt.hashi.user.service;

import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.dto.OnboardingResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OnboardingService {

    private final UserRepository userRepository;
    private final AuthAccountPort authAccountPort;

    public OnboardingService(UserRepository userRepository, AuthAccountPort authAccountPort) {
        this.userRepository = userRepository;
        this.authAccountPort = authAccountPort;
    }

    /**
     * 온보딩 프로필을 저장해 가입을 완료한다. 회원 생성과 소셜 계정 연결을 한 트랜잭션에서 처리해
     * 둘 중 하나만 남는 상태를 막는다(연결 대상 제공자·식별자는 auth가 온보딩 컨텍스트에서 읽는다).
     */
    @Transactional
    public OnboardingResponse completeOnboarding(CompleteOnboardingRequest request) {
        validateNotDuplicated(request);
        User user = saveUser(request);
        authAccountPort.linkOnboardingAccount(user.getId());
        // 회원 생성 시점 기록 — 프로필 값은 개인정보라 ID만 남긴다
        log.info("온보딩 가입 완료. userId={}", user.getId());
        return new OnboardingResponse(user.getId());
    }

    /** 닉네임·이메일·연락처는 유니크다. 일반적인 경우 어느 필드가 중복인지 구체적으로 알려준다. */
    private void validateNotDuplicated(CompleteOnboardingRequest request) {
        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw new BusinessException(UserErrorCode.DUPLICATE_PHONE);
        }
    }

    private User saveUser(CompleteOnboardingRequest request) {
        try {
            return userRepository.save(User.onboard(
                    request.nickname(), request.nameEng(), request.birthDate(),
                    request.phone(), request.email(), request.profileImageKey()));
        } catch (DataIntegrityViolationException e) {
            // 사전 검사와 저장 사이의 동시 가입 경합 — 유니크 제약(nickname·email·phone)이 최종 방어한다.
            // 롤백된 트랜잭션에서 어느 필드인지 재조회하는 건 불안정하므로 일반 충돌로 변환한다.
            throw new BusinessException(UserErrorCode.DUPLICATE_USER_INFO, e);
        }
    }
}
