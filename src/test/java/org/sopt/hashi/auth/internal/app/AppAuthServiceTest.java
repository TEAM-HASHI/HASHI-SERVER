package org.sopt.hashi.auth.internal.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.auth.internal.account.AuthAccountService;
import org.sopt.hashi.auth.internal.account.AuthProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.kakao.KakaoAppClient;
import org.sopt.hashi.auth.internal.token.SessionTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.shared.error.BusinessException;

class AppAuthServiceTest {
    private final KakaoAppClient kakao = mock(KakaoAppClient.class);
    private final AuthAccountService accounts = mock(AuthAccountService.class);
    private final JwtProperties properties = new JwtProperties("test-secret-must-be-more-than-32-bytes",
            Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(30));
    private final JwtProvider jwt = new JwtProvider(properties);
    private final SessionTokenStore sessions = mock(SessionTokenStore.class);
    private final TokenBlacklist blacklist = mock(TokenBlacklist.class);
    private final AppAuthService service = new AppAuthService(kakao, accounts, jwt, properties, sessions, blacklist);
    private static final String SID = "01234567-1234-1234-1234-012345678901";

    @Test
    void 회원_로그인은_앱_sid_토큰을_발급한다() {
        when(kakao.verify("sdk")).thenReturn(17L);
        when(accounts.findUserId(AuthProvider.KAKAO, "17")).thenReturn(Optional.of(7L));
        var result = service.login("sdk");
        assertThat(result.registered()).isTrue();
        var claims = jwt.parse(result.tokens().refreshToken());
        assertThat(claims.isApp()).isTrue();
        assertThat(claims.sid()).isEqualTo(jwt.parse(result.tokens().accessToken()).sid());
        verify(sessions).save(7L, claims.sid(), "app", result.tokens().refreshToken());
        assertThat(result.tokens().accessExpiresIn()).isEqualTo(1800);
    }

    @Test
    void 미가입자는_정식_토큰을_받지_않는다() {
        when(kakao.verify("sdk")).thenReturn(17L);
        when(accounts.findUserId(AuthProvider.KAKAO, "17")).thenReturn(Optional.empty());
        var result = service.login("sdk");
        assertThat(result.registered()).isFalse();
        assertThat(result.tokens()).isNull();
        verifyNoInteractions(sessions);
    }

    @Test
    void 탈퇴_계정은_앱_로그인을_거절한다() {
        when(kakao.verify("sdk")).thenReturn(17L);
        when(accounts.findUserId(AuthProvider.KAKAO, "17")).thenReturn(Optional.of(7L));
        when(blacklist.isUserBlocked(7L)).thenReturn(true);
        assertThatThrownBy(() -> service.login("sdk")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sessions);
    }

    @Test
    void 재발급은_같은_sid로_회전하고_로그아웃은_해당_앱을_폐기한다() {
        String refresh = jwt.createSessionRefreshToken(7L, SID, "app");
        var result = service.reissue(refresh);
        assertThat(jwt.parse(result.refreshToken()).sid()).isEqualTo(SID);
        assertThat(result.refreshToken()).isNotEqualTo(refresh);
        verify(sessions).rotate(7L, SID, "app", refresh, result.refreshToken());
        service.logout(result.refreshToken());
        verify(sessions).revoke(7L, SID, "app", result.refreshToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"web", "admin", "legacy", "access", "onboarding"})
    void 앱_재발급은_다른_종류_토큰을_거절한다(String kind) {
        String token = switch (kind) {
            case "web" -> jwt.createSessionRefreshToken(7L, SID, "web");
            case "admin" -> jwt.createRefreshToken(7L, AuthRoles.ADMIN);
            case "legacy" -> jwt.createRefreshToken(7L, AuthRoles.USER);
            case "access" -> jwt.createSessionAccessToken(7L, SID, "app");
            default -> jwt.createOnboardingToken(7L);
        };
        assertThatThrownBy(() -> service.reissue(token)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.logout(token)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sessions);
    }

    @Test
    void 코드_발급은_앱_access만_받고_충분히_긴_임의값을_발급한다() {
        String access = jwt.createSessionAccessToken(7L, SID, "app");
        var result = service.issueCode(access, "challenge");
        assertThat(result.code()).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.expiresIn()).isEqualTo(60);
        verify(sessions).issueCode(7L, SID, result.code(), "challenge");
        assertThatThrownBy(() -> service.issueCode(jwt.createSessionRefreshToken(7L, SID, "app"), "challenge"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.issueCode(jwt.createAccessToken(7L, AuthRoles.USER), "challenge"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 교환은_부모와_다른_웹_sid_리프레시만_발급한다() {
        var bridge = new SessionTokenStore.BridgeCode("7", SID, "challenge");
        when(sessions.findCode("code")).thenReturn(bridge);
        String refresh = service.exchange("code", "verifier");
        var claims = jwt.parse(refresh);
        assertThat(claims.client()).isEqualTo("web");
        assertThat(claims.sid()).isNotEqualTo(SID);
        assertThat(claims.isRefreshToken()).isTrue();
        verify(sessions).exchange(eq(bridge), eq("code"), eq("verifier"), anyString(), eq(refresh));
    }
}
