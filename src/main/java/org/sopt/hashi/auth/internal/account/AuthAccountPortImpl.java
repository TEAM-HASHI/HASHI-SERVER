package org.sopt.hashi.auth.internal.account;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;

import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 온보딩 컨텍스트의 소셜 계정 식별자를 회원과 연결하고, 탈퇴 회원의 계정·토큰을 정리한다.
 * 제공자 식별자(kakaoId)는 온보딩 임시 인증의 principal에서 읽으므로, user는 제공자를 알 필요가 없다.
 */
@Component
class AuthAccountPortImpl implements AuthAccountPort {

    private final AuthAccountService authAccountService;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklist tokenBlacklist;

    AuthAccountPortImpl(AuthAccountService authAccountService,
                        RefreshTokenStore refreshTokenStore,
                        TokenBlacklist tokenBlacklist) {
        this.authAccountService = authAccountService;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenBlacklist = tokenBlacklist;
    }

    @Override
    public void linkOnboardingAccount(Long userId) {
        // 현재 provider는 카카오뿐이다. 제공자가 늘어나면 온보딩 토큰에 provider를 실어 이 값을 결정한다.
        authAccountService.link(AuthProvider.KAKAO, String.valueOf(currentOnboardingKakaoId()), userId);
    }

    /**
     * 계정 삭제는 탈퇴 트랜잭션에 참여하고, 토큰 폐기·블랙리스트는 Redis라 커밋 전에 바로 반영된다 — 커밋 직후
     * 잔여 토큰이 통과하는 틈을 없애기 위해서다. 대신 탈퇴가 롤백되면 블랙리스트를 되돌려 회원이 묶이지 않게 한다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void unlinkWithdrawnAccount(Long userId) {
        authAccountService.unlink(userId);
        refreshTokenStore.revoke(AuthRoles.USER, userId);
        tokenBlacklist.blockUser(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    tokenBlacklist.unblockUser(userId);
                }
            }
        });
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
