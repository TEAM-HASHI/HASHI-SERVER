package org.sopt.hashi.restaurant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
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

@Tag(name = "지도", description = "공개 지도 조회와 조회 세션 API")
@RestController
public class RestaurantMapPageController {
    private final RestaurantMapPageService service;

    public RestaurantMapPageController(RestaurantMapPageService service) {
        this.service = service;
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = RestaurantErrorCode.class, codes = {"UNSUPPORTED_GENRE", "UNSUPPORTED_SORT",
            "UNSUPPORTED_PLACE_TYPE", "MAP_BOUNDS_INVALID", "MAP_REGION_INVALID", "MAP_SESSION_EXPIRED",
            "MAP_SESSION_UNAVAILABLE", "MAP_QUERY_UNAVAILABLE", "MAP_CAPACITY_EXCEEDED",
            "MAP_CONFIGURATION_UNAVAILABLE", "MAP_RATE_LIMITED"})
    @Operation(summary = "지도 식당 목록 조회", description = """
            요청은 세 모드 중 하나만 사용한다. 새 조회는 south/north/west/east와 선택 필터를 보내고,
            정렬 변경은 querySessionId와 sort만, 다음 페이지는 cursor만 보낸다. 전체 음식점 분류는
            placeType을 생략하며 keyword는 앞뒤 공백을 제거한 뒤 식당명/메뉴명에서 리터럴 부분 검색한다.
            응답 content는 최대 10개이고 같은 항목이 목록과 핀의 기준이다. 조건에 맞는 식당이 없으면
            오류 대신 content=[]인 200을 반환한다. 410이면 마지막 성공 조회 조건으로 새 조회를 시작하고,
            429는 최대 60초 뒤, 503은 기존 결과를 유지한 채 같은 요청을 재시도한다.
            서버 세션은 정상 조회마다 유휴 5분을 갱신하되 최초 admission부터 최대 30분까지만 유지한다.
            화면 상태를 5분 보관하는 클라이언트 정책과 서버 세션 수명은 서로 다른 책임이다.
            """)
    @Parameters({
            @Parameter(name = "south", in = ParameterIn.QUERY,
                    description = "새 조회 필수. 현재 viewport의 남쪽 위도",
                    schema = @Schema(type = "number", example = "35.0")),
            @Parameter(name = "north", in = ParameterIn.QUERY,
                    description = "새 조회 필수. 현재 viewport의 북쪽 위도",
                    schema = @Schema(type = "number", example = "35.1")),
            @Parameter(name = "west", in = ParameterIn.QUERY,
                    description = "새 조회 필수. 현재 viewport의 서쪽 경도",
                    schema = @Schema(type = "number", example = "139.0")),
            @Parameter(name = "east", in = ParameterIn.QUERY,
                    description = "새 조회 필수. 현재 viewport의 동쪽 경도",
                    schema = @Schema(type = "number", example = "139.1")),
            @Parameter(name = "mapRegionId", in = ParameterIn.QUERY,
                    description = "새 조회 선택. 활성 관광 지역 ID",
                    schema = @Schema(type = "integer", format = "int64", example = "1")),
            @Parameter(name = "keyword", in = ParameterIn.QUERY,
                    description = "새 조회 선택. 앞뒤 공백 제거, 내부 공백 보존, 최대 100자. "
                    + "%, _, !도 와일드카드가 아닌 글자 그대로 식당명/메뉴명에서 부분 검색",
                    schema = @Schema(type = "string", example = "sushi")),
            @Parameter(name = "genre", in = ParameterIn.QUERY, description = "새 조회 선택. 음식 장르",
                    schema = @Schema(type = "string", allowableValues = {"sushi", "noodle", "rice-bowl",
                            "nabe", "fried", "grill", "etc"}, example = "sushi")),
            @Parameter(name = "placeType", in = ParameterIn.QUERY,
                    description = "새 조회 선택. 음식점 분류. 전체(ALL)는 이 파라미터를 생략",
                    schema = @Schema(type = "string", allowableValues = {"restaurant", "cafe", "bar"},
                            example = "restaurant")),
            @Parameter(name = "sort", in = ParameterIn.QUERY,
                    description = "새 조회에서는 선택(기본 recommend). 정렬 변경에서는 "
                    + "querySessionId와 함께 필수",
                    schema = @Schema(type = "string", allowableValues = {"recommend", "rating", "reviews"},
                            defaultValue = "recommend")),
            @Parameter(name = "querySessionId", in = ParameterIn.QUERY,
                    description = "정렬 변경 전용. 직전 응답의 조회 세션 ID",
                    schema = @Schema(type = "string", format = "uuid",
                            example = "123e4567-e89b-12d3-a456-426614174000")),
            @Parameter(name = "cursor", in = ParameterIn.QUERY,
                    description = "다음 페이지 전용. 직전 응답의 nextCursor를 그대로 전달",
                    schema = @Schema(type = "string", example = "AQEj5FZ-ibEtOkVkJmFBdAAAAAAQ"))
    })
    @GetMapping("/api/v1/restaurants/map")
    public SuccessResponse<RestaurantMapPageResponse> getPage(
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters,
            HttpServletResponse response, HttpServletRequest request) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK,
                service.getPage(RestaurantMapPageRequest.from(parameters), request.getRemoteAddr()));
    }
}
