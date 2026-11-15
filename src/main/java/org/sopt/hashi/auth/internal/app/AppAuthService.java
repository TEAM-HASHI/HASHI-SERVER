package org.sopt.hashi.auth.internal.app;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.internal.UserAuthService.TokenPair;
import org.sopt.hashi.auth.internal.account.AuthAccountService;
import org.sopt.hashi.auth.internal.account.AuthProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.kakao.KakaoAppClient;
import org.sopt.hashi.auth.internal.token.SessionTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class AppAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final KakaoAppClient kakao;
    private final AuthAccountService accounts;
    private final JwtProvider jwt;
    private final JwtProperties properties;
    private final SessionTokenStore sessions;
    private final TokenBlacklist blacklist;

    public AppAuthService(KakaoAppClient kakao, AuthAccountService accounts, JwtProvider jwt,
                          JwtProperties properties, SessionTokenStore sessions, TokenBlacklist blacklist) {
        this.kakao = kakao;
        this.accounts = accounts;
        this.jwt = jwt;
        this.properties = properties;
        this.sessions = sessions;
        this.blacklist = blacklist;
    }

    public AppLoginResponse login(String kakaoAccessToken) {
        Long kakaoId = kakao.verify(kakaoAccessToken);
        Long userId = accounts.findUserId(AuthProvider.KAKAO, kakaoId.toString()).orElse(null);
        if (userId == null) {
            return new AppLoginResponse(false, null);
        }
        rejectBlocked(userId);
        String sid = UUID.randomUUID().toString();
        TokenPair tokens = tokens(userId, sid, "app");
        sessions.save(userId, sid, "app", tokens.refreshToken());
        return new AppLoginResponse(true, response(tokens));
    }

    public AppTokenResponse reissue(String refreshToken) {
        var claims = appClaims(refreshToken, false);
        TokenPair tokens = tokens(claims.subjectId(), claims.sid(), "app");
        sessions.rotate(claims.subjectId(), claims.sid(), "app", refreshToken, tokens.refreshToken());
        return response(tokens);
    }

    public void logout(String refreshToken) {
        var claims = appClaims(refreshToken, false);
        sessions.revoke(claims.subjectId(), claims.sid(), "app", refreshToken);
    }

    public WebviewCodeResponse issueCode(String accessToken, String challenge) {
        var claims = appClaims(accessToken, true);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.issueCode(claims.subjectId(), claims.sid(), code, challenge);
        return new WebviewCodeResponse(code, 60);
    }

    public String exchange(String code, String verifier) {
        var bridge = sessions.findCode(code);
        Long userId = Long.valueOf(bridge.userId());
        rejectBlocked(userId);
        String sid = UUID.randomUUID().toString();
        String refresh = jwt.createSessionRefreshToken(userId, sid, "web");
        sessions.exchange(bridge, code, verifier, sid, refresh);
        return refresh;
    }

    private JwtProvider.JwtClaims appClaims(String token, boolean access) {
        var claims = jwt.parse(token);
        if (!claims.isApp() || claims.sid() == null || !AuthRoles.USER.equals(claims.role())
                || (access ? !claims.isAccessToken() : !claims.isRefreshToken())) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        rejectBlocked(claims.subjectId());
        return claims;
    }

    private void rejectBlocked(Long userId) {
        if (blacklist.isUserBlocked(userId)) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
    }

    private TokenPair tokens(Long userId, String sid, String client) {
        return new TokenPair(jwt.createSessionAccessToken(userId, sid, client),
                jwt.createSessionRefreshToken(userId, sid, client));
    }

    private AppTokenResponse response(TokenPair tokens) {
        return new AppTokenResponse(tokens.accessToken(), tokens.refreshToken(),
                properties.accessTokenTtl().toSeconds(), properties.refreshTokenTtl().toSeconds());
    }

    public record AppLoginResponse(boolean registered, AppTokenResponse tokens) { }
    public record AppTokenResponse(String accessToken, String refreshToken,
                                   long accessExpiresIn, long refreshExpiresIn) {
        @Override public String toString() { return "AppTokenResponse[redacted]"; }
    }
    public record WebviewCodeResponse(String code, long expiresIn) {
        @Override public String toString() { return "WebviewCodeResponse[redacted]"; }
    }
}
