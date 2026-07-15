package org.sopt.hashi.shared.error;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반 예외. {@link ErrorCode}를 담아 던지면 전역 핸들러가 응답으로 변환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 원인 예외를 보존해 스택 트레이스 추적을 가능하게 한다(래핑 시 사용). */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
