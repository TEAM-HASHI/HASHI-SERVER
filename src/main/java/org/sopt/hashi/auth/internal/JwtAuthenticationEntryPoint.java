package org.sopt.hashi.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.ErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 미인증(401) 응답 변환. 필터 체인에서 발생한 인증 실패는 GlobalExceptionHandler에 닿지 않으므로
 * 여기서 ErrorResponse 봉투로 직접 직렬화한다. 토큰 검증 실패 원인이 있으면 해당 코드로 내린다.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        ErrorCode code = resolveErrorCode(request);
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, request.getRequestURI()));
    }

    private ErrorCode resolveErrorCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        if (attribute instanceof ErrorCode errorCode) {
            return errorCode;
        }
        return CommonErrorCode.UNAUTHORIZED;
    }
}
