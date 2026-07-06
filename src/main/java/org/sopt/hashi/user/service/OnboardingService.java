package org.sopt.hashi.user.service;

import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.dto.OnboardingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        User user = userRepository.save(User.onboard(
                request.nickname(), request.nameEng(), request.birthDate(),
                request.phone(), request.email(), request.profileImageKey()));
        authAccountPort.linkOnboardingAccount(user.getId());
        return new OnboardingResponse(user.getId());
    }
}
