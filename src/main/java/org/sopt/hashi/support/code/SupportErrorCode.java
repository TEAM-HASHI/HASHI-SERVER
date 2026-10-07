package org.sopt.hashi.support.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SupportErrorCode implements ErrorCode {
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "SUPPORT-001", "공지사항을 찾을 수 없습니다"),
    INVALID_NOTICE(HttpStatus.BAD_REQUEST, "SUPPORT-002", "공지사항 입력값이 올바르지 않습니다"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "SUPPORT-003", "공지사항 커서가 올바르지 않습니다");
    private final HttpStatus status;
    private final String code;
    private final String message;
}
