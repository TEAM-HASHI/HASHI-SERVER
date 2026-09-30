package org.sopt.hashi.auth.internal.web;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.kakao.KakaoLoginResponse;
import org.sopt.hashi.auth.internal.kakao.KakaoLoginRequest;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;
import org.sopt.hashi.auth.internal.UserAuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.code.AuthSuccessCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 인증 API — 카카오 로그인·토큰 재발급·로그아웃·인증 상태 조회. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserAuthService userAuthService;
    private final CookieUtil cookieUtil;
    private final OriginValidator originValidator;

    public AuthController(UserAuthService userAuthService,
                          CookieUtil cookieUtil,
                          OriginValidator originValidator) {
        this.userAuthService = userAuthService;
        this.cookieUtil = cookieUtil;
        this.originValidator = originValidator;
    }

    /** 카카오 로그인 — 기존 회원은 JWT 발급(AUTH-200), 미가입자는 온보딩 토큰 쿠키 발급(AUTH-201). */
    @ApiException(value = AuthErrorCode.class, codes = {"KAKAO_AUTH_FAILED", "KAKAO_SERVER_ERROR"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"KAKAO_LOGIN_SUCCESS", "ONBOARDING_REQUIRED"})
    @PostMapping("/kakao/login")
    public SuccessResponse<KakaoLoginResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request,
                                                          HttpServletResponse response) {
        UserAuthService.KakaoLoginResult result = userAuthService.kakaoLogin(request.code());
        if (result.registered()) {
            writeTokens(response, result.tokens());
            return SuccessResponse.of(AuthSuccessCode.KAKAO_LOGIN_SUCCESS, KakaoLoginResponse.member());
        }
        // 검증된 카카오 신원을 담은 온보딩 임시 토큰은 HttpOnly 쿠키로 이어간다(바디 노출·XSS 회피).
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieUtil.createSignupTokenCookie(result.onboardingToken()).toString());
        // 경로 확장 이전 구경로 쿠키가 남아 있으면 낡은 토큰이 먼저 선택될 수 있어 함께 만료시킨다(전환기 처리).
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieUtil.expireLegacySignupTokenCookie().toString());
        return SuccessResponse.of(AuthSuccessCode.ONBOARDING_REQUIRED, KakaoLoginResponse.onboardingRequired());
    }

    /** 토큰 재발급 — refresh 쿠키로 액세스 토큰(헤더)과 리프레시 쿠키를 갱신한다. */
    @ApiException(value = AuthErrorCode.class,
            codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND", "TOKEN_REUSE_DETECTED"})
    @ApiException(value = CommonErrorCode.class, codes = {"FORBIDDEN"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"TOKEN_REISSUED"})
    @PostMapping("/reissue")
    public SuccessResponse<Void> reissue(@RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        validateOrigin(origin);
        String presentedRefreshToken = cookieUtil.extractRefreshToken(request)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
        UserAuthService.TokenPair tokens = userAuthService.reissue(presentedRefreshToken);
        writeTokens(response, tokens);
        return SuccessResponse.of(AuthSuccessCode.TOKEN_REISSUED);
    }

    /** 로그아웃 — 리프레시 토큰을 폐기(Redis 삭제)하고 쿠키를 만료시킨다. */
    @ApiException(value = AuthErrorCode.class,
            codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND"})
    @ApiException(value = CommonErrorCode.class, codes = {"FORBIDDEN"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"LOGOUT_SUCCESS"})
    @PostMapping("/logout")
    public SuccessResponse<Void> logout(@RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        validateOrigin(origin);
        String presentedRefreshToken = cookieUtil.extractRefreshToken(request)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
        userAuthService.logout(presentedRefreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieUtil.expireRefreshTokenCookie().toString());
        return SuccessResponse.of(AuthSuccessCode.LOGOUT_SUCCESS);
    }

    /** 현재 인증 상태 조회 — role(USER/ADMIN/ONBOARDING)로 라우팅 분기용. 온보딩 토큰은 subjectId가 null. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "INVALID_ONBOARDING_TOKEN"})
    @GetMapping("/me")
    public SuccessResponse<AuthMeResponse> me(Authentication authentication) {
        // "ROLE_USER", "ROLE_ADMIN", "ROLE_ONBOARDING" 추출
        String authority = authentication.getAuthorities().iterator().next().getAuthority();
        Long subjectId = (authentication.getPrincipal() instanceof MemberPrincipal member)
                ? member.userId()
                : null;   // OnboardingPrincipal — kakaoId는 노출하지 않는다
        return SuccessResponse.of(CommonSuccessCode.OK, AuthMeResponse.of(subjectId, authority));
    }

    /**
     * 쿠키 기반 엔드포인트(reissue·logout) CSRF 방어 — 쿠키는 SameSite=None이라 교차 사이트에서도 전송되므로,
     * 브라우저가 붙이는 Origin이 허용 목록 밖이면 거부한다(Origin 없음 = 동일 출처/비브라우저 → 허용).
     */
    private void validateOrigin(String origin) {
        if (!originValidator.isAllowed(origin)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void writeTokens(HttpServletResponse response, UserAuthService.TokenPair tokens) {
        response.setHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + tokens.accessToken());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieUtil.createRefreshTokenCookie(tokens.refreshToken()).toString());
    }
}
