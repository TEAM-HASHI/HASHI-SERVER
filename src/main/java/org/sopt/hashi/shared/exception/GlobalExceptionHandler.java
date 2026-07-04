package org.sopt.hashi.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.ErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 변환 전담. 컨트롤러/서비스는 에러 응답을 직접 만들지 않고 여기서 {@link ErrorResponse}로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    // BusinessException 에러
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException e,
            HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, request.getRequestURI()));
    }
    // Validation 검증 에러
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e,
                                                                   HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorResponse.FieldError(
                        fieldError.getField(),
                        fieldError.getRejectedValue(),
                        fieldError.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT, request.getRequestURI(), errors));
    }
    // 예상치 못한 서버 내부 에러
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception e,
            HttpServletRequest request) {
        return ResponseEntity.status(CommonErrorCode.INTERNAL.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL, request.getRequestURI()));
    }
}
