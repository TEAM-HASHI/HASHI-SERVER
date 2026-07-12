package org.sopt.hashi.auth.internal.dev;

import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 개발용 테스트 토큰 발급 — local·dev 프로필에서만 빈이 등록된다(운영에는 존재 자체가 없음).
 * ONBOARDING 토큰은 필터가 Redis의 현재 토큰과 대조하므로 발급과 동시에 저장까지 해야 실제 검증을 통과한다.
 */
@Profile({"local", "dev"})
@Service
public class DevTokenService {

    private final JwtProvider jwtProvider;
    private final OnboardingTokenStore onboardingTokenStore;

    public DevTokenService(JwtProvider jwtProvider, OnboardingTokenStore onboardingTokenStore) {
        this.jwtProvider = jwtProvider;
        this.onboardingTokenStore = onboardingTokenStore;
    }

    public DevTokenResponse issue(DevTokenRole role, Long subjectId) {
        if (role == DevTokenRole.ONBOARDING) {
            String onboardingToken = jwtProvider.createOnboardingToken(subjectId);
            onboardingTokenStore.save(subjectId, onboardingToken);
            return new DevTokenResponse(onboardingToken, role.name(), subjectId);
        }
        return new DevTokenResponse(jwtProvider.createAccessToken(subjectId, role.authority()), role.name(), subjectId);
    }
}
