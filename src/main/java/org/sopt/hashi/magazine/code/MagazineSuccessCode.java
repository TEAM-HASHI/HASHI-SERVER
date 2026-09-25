package org.sopt.hashi.magazine.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

@Getter
public enum MagazineSuccessCode implements SuccessCode {

    LIKE_CREATED(HttpStatus.OK, "MAGAZINE-200", "매거진 좋아요를 등록했습니다"),
    LIKE_DELETED(HttpStatus.OK, "MAGAZINE-201", "매거진 좋아요를 취소했습니다"),
    LIKE_ALREADY_CREATED(HttpStatus.OK, "MAGAZINE-202", "이미 좋아요한 매거진입니다"),
    LIKE_ALREADY_DELETED(HttpStatus.OK, "MAGAZINE-203", "이미 좋아요하지 않은 매거진입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MagazineSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
