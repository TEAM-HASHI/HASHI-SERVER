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
@Tag(name = "지도", description = "공개 지도 조회와 조회 세션 API")
@Validated
@RestController
@RequestMapping("/api/v1/restaurants")
public class RestaurantMapController {

    private final RestaurantMapService service;

    public RestaurantMapController(RestaurantMapService service) {
        this.service = service;
    }

    @ApiException(value = RestaurantErrorCode.class,
            codes = {"MAP_CONFIGURATION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE"})
    @Operation(summary = "지도 관광 지역 조회", description = """
            최초 카메라 범위, 서버가 허용하는 조회 범위와 크기, 활성 관광 지역의 클러스터 위치와
            카메라 범위를 반환한다. 식당 수가 0인 활성 지역도 포함하며 응답은 캐시하지 않는다.
            """)
    @GetMapping("/map/regions")
    public SuccessResponse<RestaurantMapRegionsResponse> getRegions(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getRegions());
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class,
            codes = {"NOT_FOUND", "MAP_LOCATION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE"})
    @Operation(summary = "선택 식당 지도 위치 조회", description = """
            선택한 공개 식당의 현재 유효한 좌표와 UTC 만료 시각을 반환한다. 삭제·비노출 식당은 404,
            식당은 존재하지만 표시 가능한 좌표가 없거나 만료됐으면 409를 반환한다.
            """)
    @GetMapping("/{restaurantId}/map-location")
    public SuccessResponse<RestaurantMapLocationResponse> getLocation(
            @Parameter(description = "조회할 식당 ID", example = "1")
            @Positive @PathVariable Long restaurantId, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getLocation(restaurantId));
    }
}
