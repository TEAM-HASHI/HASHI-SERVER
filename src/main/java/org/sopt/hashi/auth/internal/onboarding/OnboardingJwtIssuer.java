package org.sopt.hashi.auth.internal.onboarding;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.security.CookieUtil;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.account.AuthProvider;
import org.sopt.hashi.auth.internal.account.AuthAccountService;

import org.sopt.hashi.shared.response.SuccessResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 온보딩 저장 성공 응답에 정식 세션(access 헤더 + refresh 쿠키)을 실어주고 소비된 signup 쿠키를 만료한다 — 가입과 동시에 로그인.
 * 세션 발급을 user가 아니라 auth가 응답 후처리로 하는 이유:
 *  (1) 토큰은 HTTP 응답 헤더·쿠키에 실리므로 서비스 계층이 아니라 응답 레이어의 관심사다.
 *  (2) ResponseBodyAdvice는 컨트롤러 반환(=온보딩 트랜잭션 커밋) 후 실행되므로, 롤백된 가입에 세션이 새어나가지 않는다.
 *  (3) user 모듈은 토큰·로그인을 알지 않는다 — "가입=로그인"은 auth가 투명하게 얹는 별개 관심사다.
 * 인터셉터가 아닌 ResponseBodyAdvice로 둔 건, 바디 직렬화 직전 훅이라 아직 응답 헤더를 바꿀 수 있어서다.
 */
@RestControllerAdvice
public class OnboardingJwtIssuer implements ResponseBodyAdvice<Object> {

    private static final String ONBOARDING_PATH = "/api/v1/users/onboarding";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthAccountService authAccountService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final OnboardingTokenStore onboardingTokenStore;
    private final CookieUtil cookieUtil;

    public OnboardingJwtIssuer(AuthAccountService authAccountService,
                               JwtProvider jwtProvider,
                               RefreshTokenStore refreshTokenStore,
                               OnboardingTokenStore onboardingTokenStore,
                               CookieUtil cookieUtil) {
        this.authAccountService = authAccountService;
        this.jwtProvider = jwtProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.onboardingTokenStore = onboardingTokenStore;
        this.cookieUtil = cookieUtil;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;   // 대상 판별은 요청 정보가 필요하므로 beforeBodyWrite에서 한다
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!isOnboardingSuccess(body, request)) {
            return body;
        }
        Long kakaoId = currentOnboardingKakaoId();
        if (kakaoId == null) {
            return body;
        }
        // 온보딩 트랜잭션에서 auth_account가 이미 연결됨 — 방어적으로 없으면 토큰 발급 없이 통과시킨다
        authAccountService.findUserId(AuthProvider.KAKAO, String.valueOf(kakaoId)).ifPresent(userId -> {
            String accessToken = jwtProvider.createAccessToken(userId, AuthRoles.USER);
            String refreshToken = jwtProvider.createRefreshToken(userId, AuthRoles.USER);
            refreshTokenStore.save(userId, refreshToken);
            response.getHeaders().set(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken);
            response.getHeaders().add(HttpHeaders.SET_COOKIE,
                    cookieUtil.createRefreshTokenCookie(refreshToken).toString());
            // 소비된 온보딩 토큰의 클라 쿠키도 제거한다.
            response.getHeaders().add(HttpHeaders.SET_COOKIE,
                    cookieUtil.expireSignupTokenCookie().toString());
            onboardingTokenStore.consume(kakaoId);   // 1회용 임시 토큰 폐기(Redis)
        });
        return body;
    }

    private boolean isOnboardingSuccess(Object body, ServerHttpRequest request) {
        return body instanceof SuccessResponse<?>
                && ONBOARDING_PATH.equals(request.getURI().getPath());
    }

    /** 온보딩 임시 인증(OnboardingPrincipal)의 kakaoId를 읽는다. 그 외 인증이면 null. */
    private Long currentOnboardingKakaoId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OnboardingPrincipal(Long kakaoId))) {
            return null;
        }
        return kakaoId;
    }
}
