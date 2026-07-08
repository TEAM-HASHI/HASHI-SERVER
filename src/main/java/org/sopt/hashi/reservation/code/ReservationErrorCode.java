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
    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION-002", "예약하려는 식당을 찾을 수 없습니다"),
    USED_POINT_EXCEEDS_FEE(HttpStatus.BAD_REQUEST, "RESERVATION-003", "사용 포인트가 결제 수수료를 초과했습니다"),
    ALREADY_CANCELED(HttpStatus.CONFLICT, "RESERVATION-004", "이미 취소된 예약입니다"),
    CANNOT_CANCEL(HttpStatus.CONFLICT, "RESERVATION-005", "취소할 수 없는 상태의 예약입니다"),
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "RESERVATION-006", "결제 금액이 수수료 계산과 일치하지 않습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ReservationErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
