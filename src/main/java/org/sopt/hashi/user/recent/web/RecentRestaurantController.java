package org.sopt.hashi.user.recent.web;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.recent.dto.RecentRestaurantListResponse;
import org.sopt.hashi.user.recent.dto.RecentRestaurantRequest;
import org.sopt.hashi.user.recent.service.RecentRestaurantService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/users/me/recent-restaurants")
class RecentRestaurantController {
    private final RecentRestaurantService service;

    RecentRestaurantController(RecentRestaurantService service) {
        this.service = service;
    }

    @Operation(summary = "최근 본 맛집 조회", description = "검색은 첫 10곳을 사용합니다. 마이는 nextCursor로 10곳씩 이어서 조회합니다.")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "INVALID_INPUT"})
    @GetMapping
    public SuccessResponse<RecentRestaurantListResponse> list(@Size(max = 80) @RequestParam(required = false) String cursor) {
        return SuccessResponse.of(CommonSuccessCode.OK, service.list(cursor));
    }

    @Operation(summary = "비회원 최근 본 맛집 병합", description = """
            로그인한 회원에게 최신순으로 최대 50건을 병합합니다. 시각은 ISO-8601이며 기기 시계가 최대 5분 빠른 경우 원래 시각을 유지합니다.
            1970년 이전이거나 허용 오차를 넘는 시각은 기록 시각이 없는 것으로 처리합니다.
            시각이 없는 이전 버전 기록은 기존 이력 뒤에 입력 순서로 추가합니다. 삭제되거나 없는 식당은 생략합니다.
            """)
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "INVALID_INPUT"})
    @PostMapping("/merge")
    public SuccessResponse<Void> merge(@Valid @RequestBody RecentRestaurantRequest request) {
        service.merge(request);
        return SuccessResponse.of(CommonSuccessCode.OK);
    }
}
