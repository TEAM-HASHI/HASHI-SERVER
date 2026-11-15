package org.sopt.hashi.restaurant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuDetailResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantSearchKeywordRecommendationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantSearchSuggestionResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 식당 조회·검색 API. */
@Validated
@RestController
@RequestMapping("/api/v1/restaurants")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    /** 식당 목록 조회 — 키워드·장르·정렬·유형 필터(커서 페이지네이션). */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class,
            codes = {"UNSUPPORTED_GENRE", "UNSUPPORTED_SORT", "UNSUPPORTED_LIST_TYPE"})
    @Operation(summary = "식당 목록 및 일반 검색", description = """
            keyword가 있으면 식당명·메뉴명·해시태그를 대소문자 구분 없이 부분 검색합니다.
            여러 단어는 OR 조건이며, 중복 단어는 한 번만 계산합니다. 예: 스시 라멘.
            #스시는 해시태그만 검색합니다. URL에서는 #을 %23으로 인코딩하세요.
            검색어 앞뒤 공백을 제거하며 최대 30자(Unicode 코드 포인트 기준)입니다. #만 있거나 태그 안에 공백이 있으면 400입니다.
            keyword 생략·공백은 기존 전체 목록 조회입니다. 초기 검색 화면에서는 클라이언트가 검색 요청을 생략합니다.
            검색 basic: 일치 단어 수 → 식당명 일치 수 → 메뉴명 일치 수 → 해시태그 일치 수 → ID 내림차순.
            popular: 리뷰 수 → 별점 → ID 내림차순. 검색 rating: 별점 → 리뷰 수 → ID 내림차순.
            keyword 없는 기존 목록의 basic(ID), rating(별점·ID) 순서는 유지합니다.
            기본 10개, 최대 50개입니다. hasNext=true일 때 nextCursor를 그대로 보내세요.
            검색어·장르·유형·정렬 변경 시 cursor를 빼고 첫 페이지부터 요청하세요. 조건이 다른 검색 커서는 400입니다.
            이전 버전의 검색 커서도 400이므로 배포 후 첫 페이지를 다시 조회하세요.
            추가 조회 중 식당 정보·별점·리뷰 수가 바뀌면 순서가 달라질 수 있습니다. 고정된 검색 스냅샷은 아닙니다.
            결과 없음은 200과 빈 content, hasNext=false이며 조회 오류와 구분됩니다.
            """)
    @GetMapping
    public SuccessResponse<RestaurantListResponse> getRestaurants(
            @Parameter(description = "일반 검색어 또는 #태그. 예: 스시 라멘 / #가성비")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "전체 all(기본), sushi, noodle, rice-bowl, nabe, fried, grill, etc")
            @RequestParam(required = false) String genre,
            @Parameter(description = "basic(기본·검색 정확도순), popular(인기순), rating(별점순)")
            @RequestParam(required = false) String sort,
            @Parameter(description = "all(기본), sns-hot, popular, hashi-pick")
            @RequestParam(name = "type", required = false) String type,
            @Size(max = 200) @RequestParam(required = false) String cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getRestaurants(keyword, genre, sort, type, cursor, size));
    }

    /** 검색어 자동완성 제안 목록 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @GetMapping("/search-suggestions")
    public SuccessResponse<RestaurantSearchSuggestionResponse> getSearchSuggestions(
            @NotBlank @RequestParam String keyword,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getSearchSuggestions(keyword, size));
    }

    /** 추천 검색 키워드 목록 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @GetMapping("/search-keyword-recommendations")
    public SuccessResponse<RestaurantSearchKeywordRecommendationResponse> getSearchKeywordRecommendations(
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getSearchKeywordRecommendations(size));
    }

    /** 전체 식당 중 현재 식당을 제외한 랜덤 추천 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"RECOMMENDATION_NOT_FOUND"})
    @GetMapping("/recommendations/random")
    public SuccessResponse<RestaurantMainResponse> getRandomRestaurantRecommendation(
            @Positive @RequestParam(required = false) Long excludeRestaurantId
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getRandomRestaurantRecommendation(excludeRestaurantId));
    }

    /** 식당 상세 요약 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/{restaurantId}/summary")
    public SuccessResponse<RestaurantMainResponse> getRestaurantSummary(
            @Positive @PathVariable Long restaurantId
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK, restaurantService.getRestaurantSummary(restaurantId));
    }

    /** 식당 매장 정보 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/{restaurantId}/store-information")
    public SuccessResponse<RestaurantStoreInformationResponse> getStoreInformation(
            @Positive @PathVariable Long restaurantId
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK, restaurantService.getStoreInformation(restaurantId));
    }

    /** 식당 메뉴 목록 조회(커서 페이지네이션). */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/{restaurantId}/menus")
    public SuccessResponse<RestaurantMenuListResponse> getRestaurantMenus(
            @Positive @PathVariable Long restaurantId,
            @Positive @RequestParam(required = false) Long excludeMenuId,
            @Positive @RequestParam(required = false) Long cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getRestaurantMenus(restaurantId, excludeMenuId, cursor, size));
    }

    /** 식당 메뉴 상세 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"NOT_FOUND", "MENU_NOT_FOUND"})
    @GetMapping("/{restaurantId}/menus/{menuId}")
    public SuccessResponse<RestaurantMenuDetailResponse> getRestaurantMenu(
            @Positive @PathVariable Long restaurantId,
            @Positive @PathVariable Long menuId
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getRestaurantMenu(restaurantId, menuId));
    }
}
