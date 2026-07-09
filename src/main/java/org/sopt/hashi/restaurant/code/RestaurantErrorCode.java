package org.sopt.hashi.restaurant.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum RestaurantErrorCode implements ErrorCode {

    UNSUPPORTED_GENRE(HttpStatus.BAD_REQUEST, "RESTAURANT-001", "지원하지 않는 음식 장르입니다."),
    UNSUPPORTED_SORT(HttpStatus.BAD_REQUEST, "RESTAURANT-002", "지원하지 않는 정렬 기준입니다."),
    UNSUPPORTED_LIST_TYPE(HttpStatus.BAD_REQUEST, "RESTAURANT-003", "지원하지 않는 식당 목록 유형입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "RESTAURANT-004", "식당을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    RestaurantErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
