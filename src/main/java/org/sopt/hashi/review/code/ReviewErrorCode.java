package org.sopt.hashi.review.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum ReviewErrorCode implements ErrorCode {

    ALREADY_REVIEWED(HttpStatus.CONFLICT, "REVIEW-001", "이미 리뷰를 작성한 예약입니다."),
    NOT_VISITED(HttpStatus.CONFLICT, "REVIEW-002", "방문 완료된 예약만 리뷰를 작성할 수 있습니다."),
    UNSUPPORTED_STATUS(HttpStatus.BAD_REQUEST, "REVIEW-003", "지원하지 않는 리뷰 상태입니다."),
    UNSUPPORTED_SORT(HttpStatus.BAD_REQUEST, "REVIEW-004", "지원하지 않는 리뷰 정렬 기준입니다."),
    UNSUPPORTED_KEYWORD(HttpStatus.BAD_REQUEST, "REVIEW-005", "지원하지 않는 리뷰 키워드입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW-006", "리뷰를 찾을 수 없습니다."),
    UNSUPPORTED_RESERVATION_TYPE(
            HttpStatus.CONFLICT,
            "REVIEW-007",
            "등록 식당 예약만 리뷰를 작성할 수 있습니다."),
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
