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
    MAP_BOUNDS_INVALID(HttpStatus.BAD_REQUEST, "RESTAURANT-011", "조회할 지도 범위가 올바르지 않습니다."),
    MAP_REGION_INVALID(HttpStatus.BAD_REQUEST, "RESTAURANT-012", "조회할 관광 지역이 없거나 비활성 상태입니다."),
    MAP_SESSION_EXPIRED(HttpStatus.GONE, "RESTAURANT-013", "지도 조회가 만료되었습니다. 새로 조회해주세요"),
    MAP_SESSION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT-014", "지도 조회를 이어갈 수 없습니다."),
    MAP_QUERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT-015", "지도 정보를 조회할 수 없습니다."),
    MAP_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT-016", "조회할 식당이 너무 많습니다."),
    MAP_CONFIGURATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT-017", "지도 설정이 준비되지 않았습니다."),
    MAP_LOCATION_UNAVAILABLE(HttpStatus.CONFLICT, "RESTAURANT-018", "현재 표시할 수 있는 식당 위치가 없습니다."),
    LOCATION_RETRY_CONFLICT(HttpStatus.CONFLICT, "RESTAURANT-019", "현재 주소의 위치 상태를 다시 확인해주세요");

    private final HttpStatus status;
    private final String code;
    private final String message;

    RestaurantErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
