package org.sopt.hashi.auth.internal;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.kakao.KakaoOAuthClient;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.account.AuthProvider;
import org.sopt.hashi.auth.internal.account.AuthAccountService;

import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 유저 인증 오케스트레이션 — 카카오 로그인(가입 여부 판정·토큰 발급)과 재발급(회전).
 */
@Service
public class UserAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final AuthAccountService authAccountService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final OnboardingTokenStore onboardingTokenStore;

    public UserAuthService(KakaoOAuthClient kakaoOAuthClient,
                           AuthAccountService authAccountService,
                           JwtProvider jwtProvider,
                           RefreshTokenStore refreshTokenStore,
                           OnboardingTokenStore onboardingTokenStore) {
        this.kakaoOAuthClient = kakaoOAuthClient;
        this.authAccountService = authAccountService;
        this.jwtProvider = jwtProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.onboardingTokenStore = onboardingTokenStore;
    }

    /** 카카오 인가코드로 로그인한다. 회원이면 정식 토큰, 비회원이면 온보딩 임시 토큰을 발급한다. */
    public KakaoLoginResult kakaoLogin(String authorizationCode) {
        Long kakaoId = kakaoOAuthClient.fetchKakaoId(authorizationCode);
        return authAccountService.findUserId(AuthProvider.KAKAO, String.valueOf(kakaoId))
                .map(this::issueMemberTokens)
                .orElseGet(() -> issueOnboardingToken(kakaoId));
    }

    /** 리프레시 쿠키로 토큰을 재발급한다. 회전 시 재사용이 감지되면 세션이 무효화된다. */
    public TokenPair reissue(String presentedRefreshToken) {
        JwtProvider.JwtClaims claims = jwtProvider.parse(presentedRefreshToken);
        if (!claims.isRefreshToken()) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        String newRefreshToken = jwtProvider.createRefreshToken(claims.subjectId(), claims.role());
        refreshTokenStore.rotate(claims.subjectId(), presentedRefreshToken, newRefreshToken);
        String newAccessToken = jwtProvider.createAccessToken(claims.subjectId(), claims.role());
        return new TokenPair(newAccessToken, newRefreshToken);
    }

    private KakaoLoginResult issueMemberTokens(Long userId) {
        String accessToken = jwtProvider.createAccessToken(userId, AuthRoles.USER);
        String refreshToken = jwtProvider.createRefreshToken(userId, AuthRoles.USER);
        refreshTokenStore.save(userId, refreshToken);
        return KakaoLoginResult.member(new TokenPair(accessToken, refreshToken));
    }

    private KakaoLoginResult issueOnboardingToken(Long kakaoId) {
        String onboardingToken = jwtProvider.createOnboardingToken(kakaoId);
        onboardingTokenStore.save(kakaoId, onboardingToken);
        return KakaoLoginResult.onboardingRequired(onboardingToken);
    }

    /** 정식 토큰 쌍 — access는 Authorization 헤더, refresh는 HttpOnly 쿠키로 내린다. */
    public record TokenPair(String accessToken, String refreshToken) {
    }

    public record KakaoLoginResult(boolean registered, TokenPair tokens, String onboardingToken) {

        static KakaoLoginResult member(TokenPair tokens) {
            return new KakaoLoginResult(true, tokens, null);
        }

        static KakaoLoginResult onboardingRequired(String onboardingToken) {
            return new KakaoLoginResult(false, null, onboardingToken);
        }
    }
}
