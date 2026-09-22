package org.sopt.hashi.auth.internal.security;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 보안 크리티컬: 온보딩 임시 인증(OnboardingPrincipal)은 "로그인 사용자"로 인정되지 않아야 한다
 * — 온보딩 토큰으로 회원 전용 도메인 로직(currentUserId)에 접근하지 못하도록 principal 타입으로 차단.
 */
class CurrentUserProviderImplTest {

    private final CurrentUserProviderImpl provider = new CurrentUserProviderImpl();

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(Object principal, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    @DisplayName("정식 로그인(MemberPrincipal)은 인증으로 인정되고 userId를 반환한다")
    void 회원_principal_인증() {
        authenticateWith(new MemberPrincipal(7L), AuthRoles.USER);

        assertThat(provider.isAuthenticatedUser()).isTrue();
        assertThat(provider.currentUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("온보딩(OnboardingPrincipal)은 로그인 사용자로 인정되지 않고 currentUserId는 UNAUTHORIZED")
    void 온보딩_principal_거부() {
        authenticateWith(new OnboardingPrincipal(555L), AuthRoles.ONBOARDING);

        assertThat(provider.isAuthenticatedUser()).isFalse();
        assertThatThrownBy(provider::currentUserId)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("인증 컨텍스트가 없으면 currentUserId는 UNAUTHORIZED")
    void 미인증_거부() {
        assertThat(provider.isAuthenticatedUser()).isFalse();
        assertThatThrownBy(provider::currentUserId)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.UNAUTHORIZED);
    }
}
