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
        return ((MemberPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
    }

    @Override
    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 정식 로그인 사용자만 인정한다 — 익명(anonymousUser)·온보딩(OnboardingPrincipal)은 principal 타입으로 걸러진다.
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof MemberPrincipal;
    }
}
