package org.sopt.hashi.auth.internal.app;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.code.AuthSuccessCode;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.security.OriginValidator;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱 토큰은 본문, WebView 토큰은 HttpOnly 쿠키로 전달한다. 기존 웹 API는 유지한다. */
@RestController
@RequestMapping("/api/v1/auth")
public class AppAuthController {
    private final AppAuthService service;
    private final CookieUtil cookies;
    private final OriginValidator origins;

    public AppAuthController(AppAuthService service, CookieUtil cookies, OriginValidator origins) {
        this.service = service;
        this.cookies = cookies;
        this.origins = origins;
    }

    /** 카카오 SDK accessToken 확인 후 앱 세션을 발급한다. 미가입자는 registered=false이며 가입은 웹에서 진행한다. */
    @ApiException(value = AuthErrorCode.class, codes = {"KAKAO_AUTH_FAILED", "KAKAO_SERVER_ERROR", "APP_AUTH_UNAVAILABLE", "SESSION_LIMIT_EXCEEDED"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"KAKAO_LOGIN_SUCCESS", "ONBOARDING_REQUIRED"})
    @PostMapping(value = "/app/kakao/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<AppAuthService.AppLoginResponse> login(@Valid @RequestBody AppLoginRequest request) {
        var result = service.login(request.kakaoAccessToken());
        return SuccessResponse.of(result.registered() ? AuthSuccessCode.KAKAO_LOGIN_SUCCESS
                : AuthSuccessCode.ONBOARDING_REQUIRED, result);
    }

    /** 앱 리프레시 토큰을 회전한다. 웹 쿠키는 읽거나 변경하지 않는다. */
    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND", "TOKEN_REUSE_DETECTED"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"TOKEN_REISSUED"})
    @PostMapping(value = "/app/reissue", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<AppAuthService.AppTokenResponse> reissue(@Valid @RequestBody AppRefreshRequest request) {
        return SuccessResponse.of(AuthSuccessCode.TOKEN_REISSUED, service.reissue(request.refreshToken()));
    }

    /** 이 앱 세션과 연결된 WebView 세션을 폐기한다. 별도 브라우저 및 다른 앱 세션은 유지한다. */
    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND", "TOKEN_REUSE_DETECTED"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"LOGOUT_SUCCESS"})
    @PostMapping(value = "/app/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<Void> logout(@Valid @RequestBody AppRefreshRequest request) {
        service.logout(request.refreshToken());
        return SuccessResponse.of(AuthSuccessCode.LOGOUT_SUCCESS);
    }

    /** 앱 access 토큰으로 60초 일회용 코드를 발급한다. codeChallenge는 verifier의 SHA-256 Base64url 값이다. */
    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_TOKEN", "EXPIRED_TOKEN", "REFRESH_TOKEN_NOT_FOUND"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"WEBVIEW_CODE_ISSUED"})
    @PostMapping(value = "/webview/code", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<AppAuthService.WebviewCodeResponse> code(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody WebviewCodeRequest request) {
        if (!authorization.startsWith("Bearer ")) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        return SuccessResponse.of(AuthSuccessCode.WEBVIEW_CODE_ISSUED,
                service.issueCode(authorization.substring(7), request.codeChallenge()));
    }

    /** 코드와 verifier를 검증하고 WebView의 refresh 쿠키만 설정한다. access는 기존 /auth/reissue에서 받는다. */
    @ApiException(value = AuthErrorCode.class, codes = {"INVALID_WEBVIEW_CODE"})
    @ApiException(value = CommonErrorCode.class, codes = {"FORBIDDEN"})
    @ApiSuccess(value = AuthSuccessCode.class, codes = {"TOKEN_REISSUED"})
    @PostMapping(value = "/webview/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<Void> exchange(@RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
                                          @Valid @RequestBody WebviewExchangeRequest request,
                                          HttpServletResponse response) {
        if (origin == null || !origins.isAllowed(origin)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        String refresh = service.exchange(request.code(), request.codeVerifier());
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.createRefreshTokenCookie(refresh).toString());
        return SuccessResponse.of(AuthSuccessCode.TOKEN_REISSUED);
    }

    public record AppLoginRequest(@NotBlank @Size(max = 4096)
                                  @Pattern(regexp = "[A-Za-z0-9._~+/-]+=*") String kakaoAccessToken) {
        @Override public String toString() { return "AppLoginRequest[redacted]"; }
    }
    public record AppRefreshRequest(@NotBlank @Size(max = 4096) String refreshToken) {
        @Override public String toString() { return "AppRefreshRequest[redacted]"; }
    }
    public record WebviewCodeRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String codeChallenge) { }
    public record WebviewExchangeRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._~-]{43,128}") String codeVerifier) {
        @Override public String toString() { return "WebviewExchangeRequest[redacted]"; }
    }
}
