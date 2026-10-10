package org.sopt.hashi.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @Operation(summary = "관광 지역 설정 목록 조회",
            description = "ADMIN 전용입니다. 활성·비활성 지역을 모두 displayOrder, mapRegionId 순으로 반환합니다. "
                    + "active=false인 지역은 공개 지역 목록과 지역 필터에서 제외되지만 관리자 목록에는 남습니다.")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    public SuccessResponse<AdminMapRegionResponse.Page> getRegions(
            @Parameter(description = "0부터 시작하는 페이지 번호. page × size가 2147483647을 넘으면 400", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.getRegions(page, size));
    }

    /** 안정적인 code로 생성·전체 교체한다. 재전송해도 같은 지역 ID를 반환한다. */
    @PutMapping("/map-regions/{code}")
    @Operation(summary = "관광 지역 생성 또는 전체 설정 교체",
            description = "ADMIN 전용입니다. code가 멱등 키이므로 같은 code로 다시 PUT하면 기존 mapRegionId와 식당 배정을 "
                    + "유지한 채 name, clusterPosition, cameraBounds, displayOrder, active를 모두 교체합니다. "
                    + "clusterPosition은 클라이언트가 지역을 선택할 때 사용할 대표 중심점이며 cameraBounds 안에 있어야 합니다. "
                    + "cameraBounds는 지역 필터와 공개 집계에서 식당 좌표를 재검사하는 범위이고 식당 소속을 자동 배정하지 않습니다. "
                    + "대표 좌표와 모든 경계는 소수 6자리로 정확히 표현되어야 합니다. 서버는 값을 반올림하지 않으며 "
                    + "35.6595001처럼 추가 정밀도가 필요한 값은 400입니다. "
                    + "active=true이면 cameraBounds가 서버 지원 범위 안에 있고 위도·경도 폭이 각각 1도 이하여야 합니다. "
                    + "GET /api/v1/restaurants/map/regions의 queryLimits로 지원 범위를 확인하세요. "
                    + "active=false는 지원 범위·1도 폭 검사를 적용하지 않지만 좌표 정밀도와 대표 좌표 포함 조건은 유지합니다. "
                    + "active=false로 바꾸면 공개 지역 목록에서 빠지고, 해당 지역을 참조하는 신규 조회와 기존 지도 세션의 "
                    + "다음 페이지가 RESTAURANT-012로 거절됩니다.")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "RESTAURANT-011",
            message = "조회할 지도 범위가 올바르지 않습니다.")
    @ApiErrorResponse(status = HttpStatus.SERVICE_UNAVAILABLE, code = "RESTAURANT-017",
            message = "지도 설정이 준비되지 않았습니다.")
    public SuccessResponse<AdminMapRegionResponse> upsert(
            @Parameter(description = "안정적인 지역 코드. 대문자 영문으로 시작하는 대문자·숫자·밑줄 1~40자",
                    example = "TOKYO_SHIBUYA")
            @PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
                    name = "시부야 활성 지역 설정",
                    value = """
                            {"name":"시부야","clusterPosition":{"latitude":35.659500,"longitude":139.700500},
                            "cameraBounds":{"south":35.640000,"north":35.680000,
                            "west":139.680000,"east":139.720000},"displayOrder":10,"active":true}
                            """)))
            @Valid @RequestBody UpsertMapRegionRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.upsert(code, request));
    }

    /** 지역 소속을 명시적으로 지정한다. mapRegionId:null은 해제이며 cameraBounds로 자동 분류하지 않는다. */
    @PutMapping("/restaurants/{restaurantId}/map-region")
    @Operation(summary = "식당 관광 지역 배정 또는 해제",
            description = "ADMIN 전용입니다. 식당 소속은 좌표나 cameraBounds에서 계산하지 않고 mapRegionId로 직접 지정합니다. "
                    + "비활성 지역에도 공개 전에 미리 배정할 수 있습니다. mapRegionId:null은 소속만 해제하며 주소와 좌표는 유지합니다.")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004", message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-023", message = "관광 지역을 찾을 수 없습니다.")
    public SuccessResponse<AdminMapRegionResponse.Assignment> assign(
            @Parameter(description = "배정하거나 해제할 식당 ID", example = "1001")
            @PathVariable @Positive Long restaurantId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
                    @ExampleObject(name = "지역 배정", value = "{\"mapRegionId\":12}"),
                    @ExampleObject(name = "지역 소속 해제", value = "{\"mapRegionId\":null}")
            }))
            @Valid @RequestBody SetRestaurantMapRegionRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, service.assign(restaurantId, request));
    }
}
