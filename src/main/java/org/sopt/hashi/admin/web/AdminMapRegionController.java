package org.sopt.hashi.admin.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.admin.dto.AdminMapRegionResponse;
import org.sopt.hashi.admin.dto.SetRestaurantMapRegionRequest;
import org.sopt.hashi.admin.dto.UpsertMapRegionRequest;
import org.sopt.hashi.admin.service.AdminMapRegionService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.sopt.hashi.shared.swagger.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관광 지역 운영 API. 기존 /api/v1/admin/** 권한 규칙으로 ADMIN만 접근한다. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMapRegionController {
    private final AdminMapRegionService service;

    public AdminMapRegionController(AdminMapRegionService service) {
        this.service = service;
    }

    /** 비활성 초안까지 표시 순서·ID 순으로 조회한다. page는 0부터 시작한다. */
    @GetMapping("/map-regions")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    public SuccessResponse<AdminMapRegionResponse.Page> getRegions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getRegions(page, size));
    }

    /** 안정적인 code로 생성·전체 교체한다. 재전송해도 같은 지역 ID를 반환한다. */
    @PutMapping("/map-regions/{code}")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "RESTAURANT-011",
            message = "조회할 지도 범위가 올바르지 않습니다.")
    @ApiErrorResponse(status = HttpStatus.SERVICE_UNAVAILABLE, code = "RESTAURANT-017",
            message = "지도 설정이 준비되지 않았습니다.")
    public SuccessResponse<AdminMapRegionResponse> upsert(
            @PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String code,
            @Valid @RequestBody UpsertMapRegionRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.upsert(code, request));
    }

    /** 지역 소속을 명시적으로 지정한다. mapRegionId:null은 해제이며 cameraBounds로 자동 분류하지 않는다. */
    @PutMapping("/restaurants/{restaurantId}/map-region")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004", message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-023", message = "관광 지역을 찾을 수 없습니다.")
    public SuccessResponse<AdminMapRegionResponse.Assignment> assign(@PathVariable @Positive Long restaurantId,
            @Valid @RequestBody SetRestaurantMapRegionRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.assign(restaurantId, request));
    }
}
