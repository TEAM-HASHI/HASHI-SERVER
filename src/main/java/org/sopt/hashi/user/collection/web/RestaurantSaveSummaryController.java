package org.sopt.hashi.user.collection.web;

import java.util.List;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.user.collection.dto.MyRestaurantSavesResponse;
import org.sopt.hashi.user.collection.dto.RestaurantSaveCountsResponse;
import org.sopt.hashi.user.collection.service.RestaurantSaveSummaryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RestaurantSaveSummaryController {
    private final RestaurantSaveSummaryService service;

    public RestaurantSaveSummaryController(RestaurantSaveSummaryService service) {
        this.service = service;
    }

    /** 중복 없는 양의 식당 ID 1~100개. 삭제·미존재 식당은 제외하고 요청 순서를 유지한다. */
    @GetMapping("/api/v1/restaurants/save-counts")
    public ResponseEntity<SuccessResponse<RestaurantSaveCountsResponse>> getSaveCounts(
            @RequestParam List<Long> restaurantIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getSaveCounts(restaurantIds)));
    }

    /** 현재 로그인 회원의 모든 컬렉션을 기준으로 저장 여부를 조회한다. */
    @GetMapping("/api/v1/users/me/restaurant-saves")
    public ResponseEntity<SuccessResponse<MyRestaurantSavesResponse>> getMySaves(
            @RequestParam List<Long> restaurantIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getMySaves(restaurantIds)));
    }
}
