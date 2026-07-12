package org.sopt.hashi.restaurant.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
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
            codes = {"UNSUPPORTED_GENRE", "UNSUPPORTED_FOOD_CATEGORY", "UNSUPPORTED_SORT",
                    "UNSUPPORTED_LIST_TYPE"})
    @GetMapping
    public SuccessResponse<RestaurantListResponse> getRestaurants(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String foodCategory,
            @RequestParam(required = false) String sort,
            @RequestParam(name = "type", required = false) String type,
            @Size(max = 200) @RequestParam(required = false) String cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getRestaurants(keyword, genre, foodCategory, sort, type, cursor, size));
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
            @Positive @RequestParam(required = false) Long cursor,
            @Min(1) @Max(50) @RequestParam(required = false) Integer size
    ) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantService.getRestaurantMenus(restaurantId, cursor, size));
    }
}
