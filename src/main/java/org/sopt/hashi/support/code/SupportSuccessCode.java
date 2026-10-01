package org.sopt.hashi.support.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SupportSuccessCode implements SuccessCode {
    NOTICE_READ(HttpStatus.OK, "SUPPORT-200", "공지사항 조회 완료"),
    NOTICE_CREATED(HttpStatus.CREATED, "SUPPORT-201", "공지사항 초안 저장 완료"),
    NOTICE_UPDATED(HttpStatus.OK, "SUPPORT-202", "공지사항 수정 완료"),
    NOTICE_PUBLISHED(HttpStatus.OK, "SUPPORT-203", "공지사항 게시 완료"),
    NOTICE_DELETED(HttpStatus.OK, "SUPPORT-204", "공지사항 삭제 완료"),
    TERMS_READ(HttpStatus.OK, "SUPPORT-205", "이용약관 조회 완료");
    private final HttpStatus status;
    private final String code;
    private final String message;
}
