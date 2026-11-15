package org.sopt.hashi.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentActorProviderImplTest {

    private final CurrentActorProviderImpl provider = new CurrentActorProviderImpl();

    @BeforeEach
    @AfterEach
    void 인증_컨텍스트를_초기화한다() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void USER와_ADMIN은_같은_숫자_ID여도_다른_actor다() {
        authenticate(new MemberPrincipal(1L), AuthRoles.USER);
        CurrentActor user = provider.currentActor();

        authenticate(new MemberPrincipal(1L), AuthRoles.ADMIN);
        CurrentActor admin = provider.currentActor();

        assertThat(user).isEqualTo(new CurrentActor(ActorType.USER, 1L));
        assertThat(admin).isEqualTo(new CurrentActor(ActorType.ADMIN, 1L));
        assertThat(user).isNotEqualTo(admin);
    }

    @Test
    void ONBOARDING_principal을_ONBOARDING_actor로_반환한다() {
        authenticate(new OnboardingPrincipal(555L), AuthRoles.ONBOARDING);

        assertThat(provider.currentActor())
                .isEqualTo(new CurrentActor(ActorType.ONBOARDING, 555L));
    }

    @Test
    void principal과_role이_일치하지_않으면_인증을_거부한다() {
        authenticate(new OnboardingPrincipal(555L), AuthRoles.USER);

        assertUnauthorized();
    }

    @Test
    void 허용_role이_둘_이상이면_인증을_거부한다() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new MemberPrincipal(1L),
                        null,
                        List.of(
                                new SimpleGrantedAuthority(AuthRoles.USER),
                                new SimpleGrantedAuthority(AuthRoles.ADMIN)
                        )
                )
        );

        assertUnauthorized();
    }

    @Test
    void 인증_컨텍스트가_없으면_인증을_거부한다() {
        assertUnauthorized();
    }

    private void authenticate(Object principal, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(role))
                )
        );
    }

    private void assertUnauthorized() {
        assertThatThrownBy(provider::currentActor)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.UNAUTHORIZED);
    }
}
