package org.sopt.hashi.restaurant.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum RestaurantErrorCode implements ErrorCode {

    UNSUPPORTED_GENRE(HttpStatus.BAD_REQUEST, "RESTAURANT-001", "지원하지 않는 음식 장르입니다."),
    UNSUPPORTED_SORT(HttpStatus.BAD_REQUEST, "RESTAURANT-002", "지원하지 않는 정렬 기준입니다."),
    UNSUPPORTED_LIST_TYPE(HttpStatus.BAD_REQUEST, "RESTAURANT-003", "지원하지 않는 식당 목록 유형입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "RESTAURANT-004", "식당을 찾을 수 없습니다."),
    UNSUPPORTED_CURATION_TYPE(HttpStatus.BAD_REQUEST, "RESTAURANT-005", "지원하지 않는 큐레이션 유형입니다."),
    INVALID_BUSINESS_HOURS(HttpStatus.BAD_REQUEST, "RESTAURANT-006",
            "영업시간 정보가 올바르지 않습니다. 모든 요일을 중복 없이 포함하고 시간 규칙을 지켜야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    RestaurantErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
