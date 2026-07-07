package org.sopt.hashi.auth.internal.web;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.kakao.KakaoLoginResponse;
import org.sopt.hashi.auth.internal.kakao.KakaoLoginRequest;
import org.sopt.hashi.auth.internal.UserAuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.code.AuthSuccessCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @ApiException(value = AuthErrorCode.class, codes = {"KAKAO_AUTH_FAILED", "KAKAO_SERVER_ERROR"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
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
        return SuccessResponse.of(AuthSuccessCode.ONBOARDING_REQUIRED, KakaoLoginResponse.onboardingRequired());
    }

    @ApiException(value = AuthErrorCode.class,
            codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND", "TOKEN_REUSE_DETECTED"})
    @ApiException(value = CommonErrorCode.class, codes = {"FORBIDDEN"})
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

    /**
     * reissue CSRF 방어 — 쿠키는 SameSite=None이라 교차 사이트에서도 전송되므로,
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
