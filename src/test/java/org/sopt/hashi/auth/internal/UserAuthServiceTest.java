package org.sopt.hashi.auth.internal;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.kakao.KakaoOAuthClient;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.account.AuthProvider;
import org.sopt.hashi.auth.internal.account.AuthAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.internal.jwt.JwtProvider.JwtClaims;
import org.sopt.hashi.auth.internal.UserAuthService.TokenPair;
import org.sopt.hashi.shared.error.BusinessException;

/**
 * 보안 크리티컬: 재발급은 리프레시 토큰으로만 가능하고(권한 상승 차단), 매 재발급마다 회전(rotate)이 일어나야 한다.
 * 로그아웃은 유저 리프레시 토큰만 수용하고(어드민·액세스 토큰 거부) 세션을 폐기(revoke)해야 한다.
 */
class UserAuthServiceTest {

    private final KakaoOAuthClient kakaoOAuthClient = Mockito.mock(KakaoOAuthClient.class);
    private final AuthAccountService authAccountService = Mockito.mock(AuthAccountService.class);
    private final JwtProvider jwtProvider = Mockito.mock(JwtProvider.class);
    private final RefreshTokenStore refreshTokenStore = Mockito.mock(RefreshTokenStore.class);
    private final OnboardingTokenStore onboardingTokenStore = Mockito.mock(OnboardingTokenStore.class);
    private final TokenBlacklist tokenBlacklist = Mockito.mock(TokenBlacklist.class);

    private final UserAuthService service = new UserAuthService(
            kakaoOAuthClient, authAccountService, jwtProvider, refreshTokenStore, onboardingTokenStore,
            tokenBlacklist);

    @Test
    @DisplayName("재발급 시 리프레시를 회전(rotate)한다 — 재사용 감지가 걸리도록 매번 교체")
    void 재발급_회전() {
        given(jwtProvider.parse("oldRefresh"))
                .willReturn(new JwtClaims(7L, AuthRoles.USER, JwtProvider.TYPE_REFRESH));
        given(jwtProvider.createRefreshToken(7L, AuthRoles.USER)).willReturn("newRefresh");
        given(jwtProvider.createAccessToken(7L, AuthRoles.USER)).willReturn("newAccess");

        TokenPair pair = service.reissue("oldRefresh");

        assertThat(pair.refreshToken()).isEqualTo("newRefresh");
        verify(refreshTokenStore).rotate(AuthRoles.USER, 7L, "oldRefresh", "newRefresh");
    }

    @Test
    @DisplayName("액세스·온보딩 등 리프레시가 아닌 토큰으로 재발급하면 INVALID_TOKEN으로 거부하고 회전하지 않는다")
    void 재발급_리프레시아님_거부() {
        given(jwtProvider.parse("accessToken"))
                .willReturn(new JwtClaims(7L, AuthRoles.USER, JwtProvider.TYPE_ACCESS));

        assertThatThrownBy(() -> service.reissue("accessToken"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
        verify(refreshTokenStore, never()).rotate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("탈퇴(블랙리스트) 회원의 리프레시 토큰으로 재발급하면 INVALID_TOKEN으로 거부하고 회전하지 않는다")
    void 재발급_탈퇴_회원_거부() {
        given(jwtProvider.parse("withdrawnRefresh"))
                .willReturn(new JwtClaims(7L, AuthRoles.USER, JwtProvider.TYPE_REFRESH));
        given(tokenBlacklist.isUserBlocked(7L)).willReturn(true);

        assertThatThrownBy(() -> service.reissue("withdrawnRefresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
        verify(refreshTokenStore, never()).rotate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("로그아웃 시 유저 리프레시 세션을 폐기(revoke)한다 — 이후 재발급은 세션 부재로 거부된다")
    void 로그아웃_세션_폐기() {
        given(jwtProvider.parse("userRefresh"))
                .willReturn(new JwtClaims(7L, AuthRoles.USER, JwtProvider.TYPE_REFRESH));

        service.logout("userRefresh");

        verify(refreshTokenStore).revoke(AuthRoles.USER, 7L);
    }

    @Test
    @DisplayName("어드민 리프레시 토큰으로 로그아웃하면 INVALID_TOKEN으로 거부하고 세션을 폐기하지 않는다")
    void 로그아웃_어드민_리프레시_거부() {
        given(jwtProvider.parse("adminRefresh"))
                .willReturn(new JwtClaims(7L, AuthRoles.ADMIN, JwtProvider.TYPE_REFRESH));

        assertThatThrownBy(() -> service.logout("adminRefresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
        verify(refreshTokenStore, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("액세스 토큰으로 로그아웃하면 INVALID_TOKEN으로 거부하고 세션을 폐기하지 않는다")
    void 로그아웃_액세스_토큰_거부() {
        given(jwtProvider.parse("accessToken"))
                .willReturn(new JwtClaims(7L, AuthRoles.USER, JwtProvider.TYPE_ACCESS));

        assertThatThrownBy(() -> service.logout("accessToken"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_TOKEN);
        verify(refreshTokenStore, never()).revoke(any(), any());
    }
}
