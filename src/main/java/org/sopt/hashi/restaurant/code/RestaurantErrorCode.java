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
            "영업시간 정보가 올바르지 않습니다. 모든 요일을 중복 없이 포함하고 시간 규칙을 지켜야 합니다."),
    // RESTAURANT-007(UNSUPPORTED_FOOD_CATEGORY)은 foodCategory 자유 텍스트 전환(#145)으로 폐기 — 번호 재사용 금지
    RECOMMENDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESTAURANT-008", "추천 가능한 식당이 없습니다."),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "RESTAURANT-009", "메뉴를 찾을 수 없습니다."),
    UNSUPPORTED_PLACE_TYPE(HttpStatus.BAD_REQUEST, "RESTAURANT-010", "지원하지 않는 음식점 분류입니다."),
    // 011~018 are reserved by Map Contract v1 for the map query implementation.
    LOCATION_RETRY_CONFLICT(HttpStatus.CONFLICT, "RESTAURANT-019", "현재 주소의 위치 상태를 다시 확인해주세요"),
    // RESTAURANT-011~019는 진행 중 기능 브랜치에 이미 배정돼 있어 #230은 020부터 쓴다 — 번호 재사용 금지
    DUPLICATE_NAME(HttpStatus.CONFLICT, "RESTAURANT-020", "이미 등록된 식당명입니다."),
    DUPLICATE_ADDRESS(HttpStatus.CONFLICT, "RESTAURANT-021", "이미 등록된 주소입니다."),
    PLACES_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT-022", "Places 위치 확인을 사용할 수 없습니다"),
    PLACES_BUDGET_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "RESTAURANT-023", "Places 호출 한도를 확인해주세요"),
    PLACES_PROVIDER_FAILED(HttpStatus.BAD_GATEWAY, "RESTAURANT-024", "Places 응답을 확인할 수 없습니다"),
    PLACE_SELECTION_INVALID(HttpStatus.BAD_REQUEST, "RESTAURANT-025", "Places 선택 정보가 올바르지 않습니다"),
    PLACE_SELECTION_CONFLICT(HttpStatus.CONFLICT, "RESTAURANT-026", "현재 위치 상태를 다시 확인해주세요");

    private final HttpStatus status;
    private final String code;
    private final String message;

    RestaurantErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
