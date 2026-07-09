package org.sopt.hashi.review.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum ReviewErrorCode implements ErrorCode {

    UNSUPPORTED_SORT(HttpStatus.BAD_REQUEST, "REVIEW-004", "지원하지 않는 리뷰 정렬 기준입니다."),
    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "RESTAURANT-004", "식당을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ReviewErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
