package org.sopt.hashi.auth.internal.security;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SecurityContext에서 현재 사용자를 읽는 구현. SecurityContextHolder 접근은 이 클래스 뒤로만 숨긴다.
 */
@Component
class CurrentUserProviderImpl implements CurrentUserProvider {

    @Override
    public Long currentUserId() {
        if (!isAuthenticatedUser()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        return ((MemberPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
    }

    @Override
    public boolean isAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 정식 로그인 회원만 인정한다 — 익명(anonymousUser)·온보딩(OnboardingPrincipal)은 principal 타입으로,
        // 어드민은 권한(ROLE_ADMIN)으로 걸러진다. 어드민 토큰도 MemberPrincipal을 쓰므로 타입만 보면 adminId가
        // userId로 오인되어 회원 전용 조회(좋아요 여부 등)가 남의 데이터를 볼 수 있다.
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof MemberPrincipal
                && hasUserRole(authentication);
    }

    private boolean hasUserRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> AuthRoles.USER.equals(authority.getAuthority()));
    }
}
