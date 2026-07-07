package org.sopt.hashi.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer 액세스 토큰을 검증해 SecurityContext에 인증을 주입한다.
 * 검증 실패 시 인증 없이 통과시키고 실패 원인을 request attribute로 남긴다 — 401 응답은 EntryPoint가 만든다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 토큰 검증 실패 원인(ErrorCode)을 EntryPoint로 전달하는 request attribute 키. */
    static final String AUTH_ERROR_ATTRIBUTE = "authErrorCode";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final OnboardingTokenStore onboardingTokenStore;
    private final CookieUtil cookieUtil;
    private final OriginValidator originValidator;

    public JwtAuthenticationFilter(JwtProvider jwtProvider,
                                   OnboardingTokenStore onboardingTokenStore,
                                   CookieUtil cookieUtil,
                                   OriginValidator originValidator) {
        this.jwtProvider = jwtProvider;
        this.onboardingTokenStore = onboardingTokenStore;
        this.cookieUtil = cookieUtil;
        this.originValidator = originValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            // 정식 액세스 토큰 — Authorization 헤더는 교차 출처 위조가 불가하므로 CSRF 검증이 필요 없다.
            authenticate(request, header.substring(BEARER_PREFIX.length()));
        } else {
            authenticateWithSignupCookie(request);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 온보딩(가입) 요청은 signup_token HttpOnly 쿠키로 인증한다.
     * 쿠키는 브라우저가 자동 전송(SameSite=None)하므로 CSRF 방어로 Origin을 함께 검증한다.
     */
    private void authenticateWithSignupCookie(HttpServletRequest request) {
        Optional<String> signupToken = cookieUtil.extractSignupToken(request);
        if (signupToken.isEmpty()) {
            return;
        }
        if (!originValidator.isAllowed(request.getHeader(HttpHeaders.ORIGIN))) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, CommonErrorCode.FORBIDDEN);
            return;
        }
        authenticate(request, signupToken.get());
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            JwtProvider.JwtClaims claims = jwtProvider.parse(token);
            Object principal;
            if (claims.isOnboardingToken()) {
                // 온보딩 임시 토큰 — Redis의 현재 토큰과 대조(1회용·교체 감지). principal은 kakaoId.
                onboardingTokenStore.validate(claims.subjectId(), token);
                principal = new OnboardingPrincipal(claims.subjectId());
            } else if (claims.isAccessToken()) {
                principal = new MemberPrincipal(claims.subjectId());
            } else {
                throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            }
            // TODO(후속): TokenBlacklist(Redis) 대조 — 탈퇴 사용자의 잔여 액세스 토큰 차단
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(claims.role())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getErrorCode());
        }
    }
}
