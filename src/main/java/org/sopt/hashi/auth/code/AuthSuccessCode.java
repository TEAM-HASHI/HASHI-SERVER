package org.sopt.hashi.auth.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

/**
 * 인증 관련 성공 코드.
 */
@Getter
public enum AuthSuccessCode implements SuccessCode {

    KAKAO_LOGIN_SUCCESS(HttpStatus.OK, "AUTH-200", "로그인에 성공했습니다"),
    ONBOARDING_REQUIRED(HttpStatus.OK, "AUTH-201", "회원가입이 필요합니다"),
    TOKEN_REISSUED(HttpStatus.OK, "AUTH-202", "토큰이 재발급되었습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
