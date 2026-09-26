package org.sopt.hashi.restaurant.web;

import jakarta.servlet.http.HttpServletRequest;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Service 본문 밖에서 발생하는 transaction 시작/종료 실패도 지도 전용 오류로 변환한다. */
@Order(0)
@RestControllerAdvice(assignableTypes = RestaurantMapController.class)
public class RestaurantMapExceptionHandler {

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> handleQueryFailure(RuntimeException exception, HttpServletRequest request) {
        var code = RestaurantErrorCode.MAP_QUERY_UNAVAILABLE;
        return ResponseEntity.status(code.getStatus()).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(code, request.getRequestURI()));
    }
}
