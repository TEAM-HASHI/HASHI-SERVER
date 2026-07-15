package org.sopt.hashi.auth.internal.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.code.AuthSuccessCode;
import org.sopt.hashi.auth.internal.UserAuthService.TokenPair;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 어드민 인증 API — ID/PW 로그인·로그아웃. */
@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminAuthService adminAuthService;
    private final CookieUtil cookieUtil;
    private final OriginValidator originValidator;

    public AdminAuthController(AdminAuthService adminAuthService,
                               CookieUtil cookieUtil,
                               OriginValidator originValidator) {
        this.adminAuthService = adminAuthService;
        this.cookieUtil = cookieUtil;
        this.originValidator = originValidator;
    }

    /** 어드민 로그인 — 액세스 토큰은 Authorization 헤더, 리프레시는 HttpOnly 쿠키로 내려간다. */
    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_CREDENTIALS"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"ADMIN_LOGIN_SUCCESS"})
    @PostMapping("/login")
    public SuccessResponse<Void> login(@Valid @RequestBody AdminLoginRequest request,
                                       HttpServletResponse response) {
        TokenPair tokens = adminAuthService.login(request.loginId(), request.password());
        response.setHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + tokens.accessToken());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieUtil.createRefreshTokenCookie(tokens.refreshToken()).toString());
        return SuccessResponse.of(AuthSuccessCode.ADMIN_LOGIN_SUCCESS);
    }

    /** 어드민 로그아웃 — 리프레시 토큰을 무효화하고 쿠키를 만료시킨다. */
    @ApiException(value = AuthErrorCode.class,
            codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND"})
    @ApiException(value = CommonErrorCode.class, codes = {"FORBIDDEN"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"ADMIN_LOGOUT_SUCCESS"})
    @PostMapping("/logout")
    public SuccessResponse<Void> logout(@RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        if (!originValidator.isAllowed(origin)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        String presentedRefreshToken = cookieUtil.extractRefreshToken(request)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
        adminAuthService.logout(presentedRefreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieUtil.expireRefreshTokenCookie().toString());
        return SuccessResponse.of(AuthSuccessCode.ADMIN_LOGOUT_SUCCESS);
    }
}
