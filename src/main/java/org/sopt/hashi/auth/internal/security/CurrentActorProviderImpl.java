package org.sopt.hashi.auth.internal.security;

import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class CurrentActorProviderImpl implements CurrentActorProvider {

    @Override
    public CurrentActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw unauthorized();
        }

        Object principal = authentication.getPrincipal();
        boolean isUser = hasAuthority(authentication, AuthRoles.USER);
        boolean isAdmin = hasAuthority(authentication, AuthRoles.ADMIN);
        boolean isOnboarding = hasAuthority(authentication, AuthRoles.ONBOARDING);
        int matchedRoleCount = (isUser ? 1 : 0) + (isAdmin ? 1 : 0) + (isOnboarding ? 1 : 0);
        if (matchedRoleCount != 1) {
            throw unauthorized();
        }

        if (principal instanceof MemberPrincipal memberPrincipal) {
            if (isUser) {
                return new CurrentActor(ActorType.USER, memberPrincipal.userId());
            }
            if (isAdmin) {
                return new CurrentActor(ActorType.ADMIN, memberPrincipal.userId());
            }
        }
        if (principal instanceof OnboardingPrincipal onboardingPrincipal && isOnboarding) {
            return new CurrentActor(ActorType.ONBOARDING, onboardingPrincipal.kakaoId());
        }
        throw unauthorized();
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }

    private BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED);
    }
}
