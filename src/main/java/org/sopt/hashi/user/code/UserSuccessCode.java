package org.sopt.hashi.user.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

@Getter
public enum UserSuccessCode implements SuccessCode {

    ONBOARDING_COMPLETED(HttpStatus.CREATED, "USER-201", "회원가입이 완료되었습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UserSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
