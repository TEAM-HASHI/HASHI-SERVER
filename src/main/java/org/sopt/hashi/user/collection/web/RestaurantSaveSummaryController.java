package org.sopt.hashi.user.collection.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
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
    @Operation(summary = "지도 카드용 식당 저장 수 조회", description = """
            로그인 없이 지도 카드 여러 개의 저장 수를 한 번에 조회합니다.
            이 API는 카드 표시용 읽기 API이며 저장을 만들지 않습니다. USER가 카드에서 식당을 저장할 때는
            POST /api/v1/collections/{collectionId}/restaurants에 해당 restaurantId를 보내세요.
            restaurantIds는 중복 없는 양의 ID 1~100개이며, 삭제·미존재 식당은 응답에서 제외됩니다.
            같은 회원이 여러 공개·비공개 컬렉션에 저장해도 saveCount는 한 명으로 계산합니다.
            응답은 요청한 식당 순서를 유지하지만 일부 ID가 빠질 수 있으므로 배열 위치가 아닌 restaurantId로 카드와 결합하세요.
            응답은 no-store입니다.
            """)
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @GetMapping("/api/v1/restaurants/save-counts")
    public ResponseEntity<SuccessResponse<RestaurantSaveCountsResponse>> getSaveCounts(
            @Parameter(description = "중복 없는 양의 식당 ID 1~100개. 쉼표로 구분",
                    example = "1001,1002,1003")
            @RequestParam List<Long> restaurantIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getSaveCounts(restaurantIds)));
    }

    /** 현재 로그인 회원의 모든 컬렉션을 기준으로 저장 여부를 조회한다. */
    @Operation(summary = "지도 카드용 내 저장 여부 조회", description = """
            USER 토큰으로 지도 카드 여러 개가 내 컬렉션 중 하나 이상에 저장됐는지 한 번에 조회합니다.
            이 API는 저장 여부만 읽습니다. 카드의 저장 동작은 POST /api/v1/collections/{collectionId}/restaurants를 사용합니다.
            비로그인은 COMMON-401, ADMIN·ONBOARDING 토큰은 COMMON-403을 반환합니다.
            restaurantIds는 중복 없는 양의 ID 1~100개이며, 삭제·미존재 식당은 응답에서 제외됩니다.
            공개·비공개 컬렉션을 모두 확인하며 하나라도 저장돼 있으면 saved=true입니다.
            응답은 요청 순서를 유지하지만 일부 ID가 빠질 수 있으므로 배열 위치가 아닌 restaurantId로 카드와 결합하세요.
            응답은 no-store입니다.
            """)
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @GetMapping("/api/v1/users/me/restaurant-saves")
    public ResponseEntity<SuccessResponse<MyRestaurantSavesResponse>> getMySaves(
            @Parameter(description = "중복 없는 양의 식당 ID 1~100개. 쉼표로 구분",
                    example = "1001,1002,1003")
            @RequestParam List<Long> restaurantIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getMySaves(restaurantIds)));
    }
}
