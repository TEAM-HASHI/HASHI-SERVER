package org.sopt.hashi.auth.internal.account;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;

import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 온보딩 컨텍스트의 소셜 계정 식별자를 회원과 연결한다.
 * 제공자 식별자(kakaoId)는 온보딩 임시 인증의 principal에서 읽으므로, user는 제공자를 알 필요가 없다.
 */
@Component
class AuthAccountPortImpl implements AuthAccountPort {

    private final AuthAccountService authAccountService;

    AuthAccountPortImpl(AuthAccountService authAccountService) {
        this.authAccountService = authAccountService;
    }

    @Override
    public void linkOnboardingAccount(Long userId) {
        // 현재 provider는 카카오뿐이다. 제공자가 늘어나면 온보딩 토큰에 provider를 실어 이 값을 결정한다.
        authAccountService.link(AuthProvider.KAKAO, String.valueOf(currentOnboardingKakaoId()), userId);
    }

    /** 온보딩 임시 인증(OnboardingPrincipal)의 kakaoId를 읽는다. 그 외 컨텍스트면 거부한다. */
    private Long currentOnboardingKakaoId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OnboardingPrincipal(Long kakaoId))) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        return kakaoId;
    }
}
