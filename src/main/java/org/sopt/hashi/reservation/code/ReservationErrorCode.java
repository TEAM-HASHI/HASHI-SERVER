package org.sopt.hashi.reservation.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 예약 관련 에러 코드. 번호는 순번식(HTTP 상태와 무관한 안정 식별자, error-handling.md §3).
 */
@Getter
public enum ReservationErrorCode implements ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION-001", "예약을 찾을 수 없습니다"),
    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION-002", "예약하려는 식당을 찾을 수 없습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ReservationErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
