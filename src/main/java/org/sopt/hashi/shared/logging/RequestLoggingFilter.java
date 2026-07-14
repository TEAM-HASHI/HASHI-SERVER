package org.sopt.hashi.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 requestId를 MDC에 심어 한 요청의 모든 로그를 묶고, 종료 시 요청 단위 로그 한 줄을 남긴다.
 * userId는 인증이 끝나야 알 수 있어 JwtAuthenticationFilter가 {@link #USER_ID_KEY}로 심는다.
 * 시큐리티 필터 체인보다 먼저 실행돼 401/403 응답 로그에도 requestId가 붙는다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_KEY = "requestId";
    /** JwtAuthenticationFilter(auth)가 인증 성공 시 이 키로 MDC에 userId를 심는다. */
    public static final String USER_ID_KEY = "userId";

    /** 클라이언트가 에러 문의 시 이 값을 전달하면 해당 요청의 로그를 바로 찾을 수 있다. */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 액추에이터(스크레이핑·헬스체크)는 주기 호출이라 요청 로그를 남기면 노이즈만 쌓인다. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        // 소요 시간은 wall-clock(NTP 보정 시 역행 가능)이 아닌 단조 증가 시계로 측정한다
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("요청 처리 완료. method={} uri={} status={} duration={}ms",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            // 톰캣이 스레드를 재사용하므로 비우지 않으면 다음 요청 로그에 이전 요청의 값이 붙는다
            MDC.clear();
        }
    }
}
