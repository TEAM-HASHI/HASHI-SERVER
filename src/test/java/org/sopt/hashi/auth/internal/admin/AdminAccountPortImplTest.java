package org.sopt.hashi.auth.internal.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

class AdminAccountPortImplTest {

    private final AdminRepository admins = mock(AdminRepository.class);
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final AdminAccountPortImpl port = new AdminAccountPortImpl(admins, actors);

    @Test
    void 관리자에게_존재하는_계정의_현재_로그인ID만_일괄_반환한다() {
        given(actors.currentActor()).willReturn(new CurrentActor(ActorType.ADMIN, 1L));
        given(admins.findLoginIds(Set.of(7L, 8L, 9L)))
                .willReturn(List.of(projection(7L, "first_admin"), projection(9L, "renamed_admin")));

        var result = port.findLoginIds(Set.of(7L, 8L, 9L));

        assertThat(result).containsEntry(7L, "first_admin").containsEntry(9L, "renamed_admin")
                .doesNotContainKey(8L).hasSize(2);
        verify(admins).findLoginIds(Set.of(7L, 8L, 9L));
        verifyNoMoreInteractions(admins);
    }

    @Test
    void 빈_목록도_관리자_권한을_확인하고_DB를_조회하지_않는다() {
        given(actors.currentActor()).willReturn(new CurrentActor(ActorType.ADMIN, 1L));

        assertThat(port.findLoginIds(Set.of())).isEmpty();

        verify(actors).currentActor();
        verifyNoInteractions(admins);
    }

    @ParameterizedTest
    @EnumSource(value = ActorType.class, names = {"USER", "ONBOARDING"})
    void 다른_모듈에서_직접_호출해도_일반회원과_온보딩은_거부한다(ActorType actorType) {
        given(actors.currentActor()).willReturn(new CurrentActor(actorType, 1L));

        assertThatThrownBy(() -> port.findLoginIds(Set.of(7L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        verifyNoInteractions(admins);
    }

    @Test
    void 인증_정보가_없으면_로그인ID를_조회하지_않는다() {
        given(actors.currentActor()).willThrow(new BusinessException(CommonErrorCode.UNAUTHORIZED));

        assertThatThrownBy(() -> port.findLoginIds(Set.of(7L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED));

        verifyNoInteractions(admins);
    }

    private AdminRepository.AdminLoginIdProjection projection(Long id, String loginId) {
        return new AdminRepository.AdminLoginIdProjection() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getLoginId() {
                return loginId;
            }
        };
    }
}
