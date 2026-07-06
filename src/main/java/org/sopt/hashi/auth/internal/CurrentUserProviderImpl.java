package org.sopt.hashi.auth.internal;

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
        if (!isAuthenticated()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @Override
    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 익명 인증(principal="anonymousUser")을 걸러내기 위해 principal 타입까지 확인하고,
        // 온보딩 임시 인증(principal=kakaoId)은 "로그인 사용자"가 아니므로 제외한다.
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long
                && authentication.getAuthorities().stream()
                        .noneMatch(authority -> AuthRoles.ONBOARDING.equals(authority.getAuthority()));
    }
}
