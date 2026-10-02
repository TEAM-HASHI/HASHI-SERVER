package org.sopt.hashi.restaurant.web;

import jakarta.servlet.http.HttpServletResponse;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse;
import org.sopt.hashi.restaurant.service.RestaurantMapPageService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RestaurantMapPageController {
    private final RestaurantMapPageService service;

    public RestaurantMapPageController(RestaurantMapPageService service) {
        this.service = service;
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"UNSUPPORTED_GENRE", "UNSUPPORTED_SORT",
            "UNSUPPORTED_PLACE_TYPE", "MAP_BOUNDS_INVALID", "MAP_REGION_INVALID", "MAP_SESSION_EXPIRED",
            "MAP_SESSION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE", "MAP_CAPACITY_EXCEEDED", "MAP_CONFIGURATION_UNAVAILABLE"})
    @GetMapping("/api/v1/restaurants/map")
    public SuccessResponse<RestaurantMapPageResponse> getPage(@RequestParam MultiValueMap<String, String> parameters,
                                                             HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getPage(RestaurantMapPageRequest.from(parameters)));
    }
}
