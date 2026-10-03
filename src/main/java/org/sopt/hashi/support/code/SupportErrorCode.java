package org.sopt.hashi.support.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SupportErrorCode implements ErrorCode {
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "SUPPORT-400", "공지사항을 찾을 수 없습니다"),
    INVALID_NOTICE(HttpStatus.BAD_REQUEST, "SUPPORT-401", "공지사항 입력값이 올바르지 않습니다"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "SUPPORT-402", "공지사항 커서가 올바르지 않습니다"),
    TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "SUPPORT-403", "이용약관을 찾을 수 없습니다"),
    INVALID_TERMS(HttpStatus.BAD_REQUEST, "SUPPORT-404", "이용약관 입력값이 올바르지 않습니다"),
    DUPLICATE_TERMS_VERSION(HttpStatus.CONFLICT, "SUPPORT-405", "이미 사용 중인 약관 버전입니다"),
    TERMS_IMMUTABLE(HttpStatus.CONFLICT, "SUPPORT-406", "게시된 약관은 변경하거나 삭제할 수 없습니다");
    private final HttpStatus status;
    private final String code;
    private final String message;
}
