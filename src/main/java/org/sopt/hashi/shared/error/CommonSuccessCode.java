package org.sopt.hashi.shared.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 도메인 무관 공통 성공 코드. 특정 도메인 의미가 없는 범용 성공(200)에 사용한다.
 */
@Getter
public enum CommonSuccessCode implements SuccessCode {

    OK(HttpStatus.OK, "COMMON-200", "요청에 성공했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CommonSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
