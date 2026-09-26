package org.sopt.hashi.restaurant.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 지도 입력 검증과 Service 본문 밖의 transaction 실패를 기존 응답 형식으로 변환한다. */
@Order(0)
@RestControllerAdvice(assignableTypes = RestaurantMapController.class)
public class RestaurantMapExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = exception.getConstraintViolations().stream()
                .map(RestaurantMapExceptionHandler::toFieldError)
                .toList();
        return invalidInput(request, errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return invalidInput(request, List.of(new ErrorResponse.FieldError(
                exception.getName(), exception.getValue(), "정수 ID를 입력해 주세요.")));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> handleQueryFailure(RuntimeException exception, HttpServletRequest request) {
        var code = RestaurantErrorCode.MAP_QUERY_UNAVAILABLE;
        return ResponseEntity.status(code.getStatus()).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(code, request.getRequestURI()));
    }

    private static ErrorResponse.FieldError toFieldError(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        String field = path.substring(path.lastIndexOf('.') + 1);
        return new ErrorResponse.FieldError(field, violation.getInvalidValue(), violation.getMessage());
    }

    private static ResponseEntity<ErrorResponse> invalidInput(
            HttpServletRequest request, List<ErrorResponse.FieldError> errors) {
        var code = CommonErrorCode.INVALID_INPUT;
        return ResponseEntity.status(code.getStatus()).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(code, request.getRequestURI(), errors));
    }
}
