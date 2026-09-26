package org.sopt.hashi.restaurant.web;

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
public class RestaurantMapController {

    private final RestaurantMapService service;

    public RestaurantMapController(RestaurantMapService service) {
        this.service = service;
    }

    @ApiException(value = RestaurantErrorCode.class,
            codes = {"MAP_CONFIGURATION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE"})
    @GetMapping("/map/regions")
    public SuccessResponse<RestaurantMapRegionsResponse> getRegions(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getRegions());
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class,
            codes = {"NOT_FOUND", "MAP_LOCATION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE"})
    @GetMapping("/{restaurantId}/map-location")
    public SuccessResponse<RestaurantMapLocationResponse> getLocation(
            @Positive @PathVariable Long restaurantId, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getLocation(restaurantId));
    }
}
