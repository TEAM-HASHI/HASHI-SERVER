package org.sopt.hashi.support.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SupportSuccessCode implements SuccessCode {
    NOTICE_READ(HttpStatus.OK, "SUPPORT-200", "공지사항 조회 완료"),
    TERMS_READ(HttpStatus.OK, "SUPPORT-205", "이용약관 조회 완료");
    private final HttpStatus status;
    private final String code;
    private final String message;
}
