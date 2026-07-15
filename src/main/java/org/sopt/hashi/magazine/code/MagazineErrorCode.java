package org.sopt.hashi.magazine.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum MagazineErrorCode implements ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "MAGAZINE-001", "매거진을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MagazineErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
