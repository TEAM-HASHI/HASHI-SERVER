package org.sopt.hashi.admin.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

/**
 * 어드민(진입점) 성공 코드. 2xx 밴드 순번식(error-handling.md §3).
 * 단순 조회 성공은 CommonSuccessCode.OK를 쓰고, 의미 있는 액션만 여기에 둔다.
 */
@Getter
public enum AdminSuccessCode implements SuccessCode {

    RESERVATION_STATUS_CHANGED(HttpStatus.OK, "ADMIN-200", "예약 상태가 변경되었습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AdminSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
