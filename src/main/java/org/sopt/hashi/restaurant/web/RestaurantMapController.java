package org.sopt.hashi.restaurant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.dto.RestaurantMapLocationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse;
import org.sopt.hashi.restaurant.service.RestaurantMapService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 지도 안내와 현재 위치 조회. 목록 페이지는 후속 조회 세션 API에서 제공한다. */
@Validated
@RestController
@RequestMapping("/api/v1/restaurants")
@Tag(name = "Restaurant Map", description = "공개 지도 초기 설정과 식당 좌표 조회 API")
public class RestaurantMapController {

    private final RestaurantMapService service;

    public RestaurantMapController(RestaurantMapService service) {
        this.service = service;
    }

    @Operation(summary = "지도 관광 지역 조회",
            description = "초기 카메라 경계, 조회 가능 범위와 활성 관광 지역의 클러스터 정보를 반환합니다.")
    @ApiException(value = RestaurantErrorCode.class,
            codes = {"MAP_CONFIGURATION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE"})
    @GetMapping("/map/regions")
    public SuccessResponse<RestaurantMapRegionsResponse> getRegions(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getRegions());
    }

    @Operation(summary = "식당 지도 좌표 조회",
            description = "선택한 공개 식당의 현재 사용 가능한 좌표와 좌표 만료 시각을 반환합니다.")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class,
            codes = {"NOT_FOUND", "MAP_LOCATION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE"})
    @GetMapping("/{restaurantId}/map-location")
    public SuccessResponse<RestaurantMapLocationResponse> getLocation(
            @Parameter(description = "조회할 식당 ID", example = "1001")
            @Positive @PathVariable Long restaurantId, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getLocation(restaurantId));
    }
}
