package org.sopt.hashi.auth.internal.security;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.logging.RequestLoggingFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer 액세스 토큰을 검증해 SecurityContext에 인증을 주입한다. 블랙리스트에 오른 회원(탈퇴 등)의 액세스 토큰은 거부한다.
 * 검증 실패 시 인증 없이 통과시키고 실패 원인을 request attribute로 남긴다 — 401 응답은 EntryPoint가 만든다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 토큰 검증 실패 원인(ErrorCode)을 EntryPoint로 전달하는 request attribute 키. */
    static final String AUTH_ERROR_ATTRIBUTE = "authErrorCode";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final OnboardingTokenStore onboardingTokenStore;
    private final TokenBlacklist tokenBlacklist;
    private final CookieUtil cookieUtil;
    private final OriginValidator originValidator;

    public JwtAuthenticationFilter(JwtProvider jwtProvider,
                                   OnboardingTokenStore onboardingTokenStore,
                                   TokenBlacklist tokenBlacklist,
                                   CookieUtil cookieUtil,
                                   OriginValidator originValidator) {
        this.jwtProvider = jwtProvider;
        this.onboardingTokenStore = onboardingTokenStore;
        this.tokenBlacklist = tokenBlacklist;
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
                rejectIfBlacklisted(claims);
                principal = new MemberPrincipal(claims.subjectId());
            } else {
                throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(claims.role())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (claims.isAccessToken()) {
                // 사용자 단위 로그 추적용 — 요청 종료 시 RequestLoggingFilter가 MDC를 clear한다.
                // 온보딩 토큰의 subjectId는 kakaoId라 userId로 오인되지 않게 심지 않는다.
                MDC.put(RequestLoggingFilter.USER_ID_KEY, String.valueOf(claims.subjectId()));
            }
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getErrorCode());
        }
    }

    /** 블랙리스트에 오른 회원(현재는 탈퇴)의 잔여 액세스 토큰 차단 — 어드민 토큰의 subject는 adminId라 USER 권한일 때만 대조한다. */
    private void rejectIfBlacklisted(JwtProvider.JwtClaims claims) {
        if (AuthRoles.USER.equals(claims.role()) && tokenBlacklist.isUserBlocked(claims.subjectId())) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
    }
}
