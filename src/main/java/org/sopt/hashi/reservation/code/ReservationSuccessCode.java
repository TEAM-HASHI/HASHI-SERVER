package org.sopt.hashi.reservation.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

/**
 * 예약 관련 성공 코드. 2xx 밴드 순번식(error-handling.md §3).
 */
@Getter
public enum ReservationSuccessCode implements SuccessCode {

    RESERVATION_CREATED(HttpStatus.CREATED, "RESERVATION-201", "예약이 완료되었습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ReservationSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
