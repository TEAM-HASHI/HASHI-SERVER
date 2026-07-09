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
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 인증 컨트롤러 — 로그인·로그아웃. 유저 인증과 동일한 토큰 전달 규칙을 따른다
 * (액세스 = Authorization 헤더, 리프레시 = HttpOnly 쿠키). permitAll(/api/v1/auth/**) 경로이며
 * 로그아웃은 리프레시 쿠키 자체를 검증하므로 별도 인증 컨텍스트가 필요 없다.
 */
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

    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_CREDENTIALS"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @PostMapping("/login")
    public SuccessResponse<Void> login(@Valid @RequestBody AdminLoginRequest request,
                                       HttpServletResponse response) {
        TokenPair tokens = adminAuthService.login(request.loginId(), request.password());
        response.setHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + tokens.accessToken());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieUtil.createRefreshTokenCookie(tokens.refreshToken()).toString());
        return SuccessResponse.of(AuthSuccessCode.ADMIN_LOGIN_SUCCESS);
    }

    /** 로그아웃 — 쿠키 기반이라 reissue와 동일하게 Origin으로 CSRF를 방어하고, 클라 쿠키도 만료시킨다. */
    @ApiException(value = AuthErrorCode.class,
            codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND"})
    @ApiException(value = CommonErrorCode.class, codes = {"FORBIDDEN"})
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
