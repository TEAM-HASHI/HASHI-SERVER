package org.sopt.hashi.user.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 회원(가입·프로필) 관련 에러 코드.
 */
@Getter
public enum UserErrorCode implements ErrorCode {

    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "USER-001", "중복된 닉네임입니다"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "USER-002", "이미 사용 중인 이메일입니다"),
    DUPLICATE_PHONE(HttpStatus.CONFLICT, "USER-003", "이미 사용 중인 연락처입니다"),
    // 사전 검사와 저장 사이의 동시 가입 경합에서, 어느 필드인지 특정할 수 없을 때의 폴백(409)
    DUPLICATE_USER_INFO(HttpStatus.CONFLICT, "USER-004", "이미 사용 중인 가입 정보입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UserErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
