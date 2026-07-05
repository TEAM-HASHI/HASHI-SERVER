package org.sopt.hashi.shared.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 도메인 무관 공통 에러 코드. 특정 도메인에 속하지 않는 400/401/403/404/500 등을 담는다.
 */
@Getter
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-400", "잘못된 요청입니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON-401", "인증이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON-403", "권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-404", "리소스를 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-405", "허용되지 않은 요청 메서드입니다"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-415", "지원하지 않는 요청 형식입니다"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "서버 오류입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CommonErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
