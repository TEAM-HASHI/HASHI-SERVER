package org.sopt.hashi.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
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

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(request, header.substring(BEARER_PREFIX.length()));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            JwtProvider.JwtClaims claims = jwtProvider.parse(token);
            if (!claims.isAccessToken()) {
                throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            }
            // TODO(후속): TokenBlacklist(Redis) 대조 — 탈퇴 사용자의 잔여 액세스 토큰 차단
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    claims.userId(), null, List.of(new SimpleGrantedAuthority(claims.role())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getErrorCode());
        }
    }
}
