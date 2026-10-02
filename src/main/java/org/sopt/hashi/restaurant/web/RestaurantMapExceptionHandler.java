package org.sopt.hashi.restaurant.web;

import jakarta.servlet.http.HttpServletRequest;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.internal.map.MapQueryFailureLogger;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Service 본문 밖의 DB/transaction 실패를 지도 조회 503 응답으로 변환한다. */
@Order(0)
@RestControllerAdvice(assignableTypes = RestaurantMapController.class)
public class RestaurantMapExceptionHandler {

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> handleQueryFailure(RuntimeException exception, HttpServletRequest request) {
        MapQueryFailureLogger.warn(exception);
        var code = RestaurantErrorCode.MAP_QUERY_UNAVAILABLE;
        return ResponseEntity.status(code.getStatus()).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(code, request.getRequestURI()));
    }

}
