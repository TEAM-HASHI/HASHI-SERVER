package org.sopt.hashi.auth.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 인증/토큰 관련 에러 코드.
 */
@Getter
public enum AuthErrorCode implements ErrorCode {

    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-001", "유효하지 않은 토큰입니다"),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-002", "만료된 토큰입니다"),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "AUTH-003", "리프레시 토큰이 존재하지 않습니다"),
    TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "AUTH-004", "이미 사용된 토큰입니다. 다시 로그인해주세요");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
