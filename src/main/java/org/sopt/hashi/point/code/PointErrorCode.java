package org.sopt.hashi.point.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 포인트 관련 에러 코드. 번호는 순번식(error-handling.md §3).
 */
@Getter
public enum PointErrorCode implements ErrorCode {

    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "POINT-001", "보유 포인트가 부족합니다"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "POINT-002", "포인트 금액은 1 이상이어야 합니다"),
    RESTORE_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "POINT-003", "복원할 포인트 사용 내역을 찾을 수 없습니다"),
    ALREADY_RESTORED(HttpStatus.CONFLICT, "POINT-004", "이미 복원된 포인트 사용 내역입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PointErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
