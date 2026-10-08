package org.sopt.hashi.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminRestaurantResponse;
import org.sopt.hashi.admin.dto.CreateRestaurantRequest;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.admin.dto.RestaurantLocationResponse;
import org.sopt.hashi.admin.dto.RestaurantLocationReviewListResponse;
import org.sopt.hashi.admin.dto.RetryRestaurantLocationRequest;
import org.sopt.hashi.admin.dto.RestaurantPlacesSearchResponse;
import org.sopt.hashi.admin.dto.SearchRestaurantPlacesRequest;
import org.sopt.hashi.admin.dto.SelectRestaurantPlaceRequest;
import org.sopt.hashi.admin.service.AdminRestaurantService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiErrorResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 어드민 식당 관리 API — 등록·수정·삭제. */
@RestController
@RequestMapping("/api/v1/admin/restaurants")
public class AdminRestaurantController {

    private final AdminRestaurantService adminRestaurantService;

    public AdminRestaurantController(AdminRestaurantService adminRestaurantService) {
        this.adminRestaurantService = adminRestaurantService;
    }

    /** 식당 등록 — 식당·메뉴 사진은 presigned URL로 업로드를 마친 S3 키로 받는다. */
    // businessHours는 minItems=7이라 자동 생성 예시가 같은 요일(MONDAY)을 7번 복제해 그대로 보내면
    // RESTAURANT-006이 난다. 복붙만으로 성공하도록 요일 7개가 모두 다른 완성형 예시를 명시한다.
    @Operation(summary = "식당 등록", description = """
            식당 정보를 저장한 뒤 지도 위치 확인 작업을 비동기로 요청합니다.
            201 응답은 식당 저장과 위치 작업 등록 성공을 뜻하며, Google 위치 확인 완료를 뜻하지 않습니다.
            응답의 locationStatus와 addressRevision을 확인하고 위치 상태 조회 API로 최신 상태를 조회하세요.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
            name = "식당 등록 예시(복붙 가능)", value = """
            {
              "name": "야키니쿠 리키마루 이케부쿠로점",
              "localName": "焼肉力丸 池袋東口店",
              "summary": "이케부쿠로의 인기 야키니쿠 전문점",
              "description": "엄선된 고기와 다양한 코스를 제공합니다.",
              "address": "東京都豊島区東池袋1-1-1 架空ビル1F",
              "geocodingAddress": "東京都豊島区東池袋1-1-1",
              "area": "이케부쿠로",
              "genre": "grill",
              "foodCategory": "야키니쿠",
              "placeType": "restaurant",
              "priceCurrency": "JPY",
              "minPrice": 3000,
              "maxPrice": 8000,
              "imageKeys": ["restaurants/a1b2c3-1.jpg"],
              "menus": [
                {
                  "name": "특선 모둠 야키니쿠",
                  "description": "엄선한 부위 5종 모둠",
                  "imageKey": "restaurant-menus/a1b2c3-menu.jpg",
                  "priceCurrency": "JPY",
                  "priceAmount": 4500,
                  "main": true
                }
              ],
              "hashtags": ["현지인맛집"],
              "curationTypes": ["sns-hot"],
              "businessHours": [
                {"dayOfWeek": "MONDAY", "openTime": "11:00", "closeTime": "22:00", "breakStart": "15:00", "breakEnd": "16:00", "closed": false},
                {"dayOfWeek": "TUESDAY", "openTime": "11:00", "closeTime": "22:00", "closed": false},
                {"dayOfWeek": "WEDNESDAY", "openTime": "11:00", "closeTime": "22:00", "closed": false},
                {"dayOfWeek": "THURSDAY", "openTime": "11:00", "closeTime": "22:00", "closed": false},
                {"dayOfWeek": "FRIDAY", "openTime": "11:00", "closeTime": "22:00", "closed": false},
                {"dayOfWeek": "SATURDAY", "openTime": "11:00", "closeTime": "22:00", "closed": false},
                {"dayOfWeek": "SUNDAY", "closed": true}
              ]
            }
            """)))
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MEDIA-001",
            message = "이미지 자산을 찾을 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-006",
            message = "현재 이미지 상태에서는 요청을 처리할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-007",
            message = "이미 사용 중이거나 사용이 끝난 이미지입니다")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MEDIA-008",
            message = "같은 이미지 자산을 중복해서 요청할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-020",
            message = "이미 등록된 식당명입니다.")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-021",
            message = "이미 등록된 주소입니다.")
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"RESTAURANT_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<AdminRestaurantResponse> create(
            @Valid @RequestBody CreateRestaurantRequest request) {
        return SuccessResponse.of(AdminSuccessCode.RESTAURANT_CREATED,
                adminRestaurantService.create(request));
    }

    /** 식당 부분 수정 — 보낸 필드만 변경하며, 컬렉션(이미지·메뉴·해시태그·큐레이션)은 전체 교체한다. */
    // 자동 생성 예시는 businessHours를 같은 요일 7개로 복제해 그대로 보내면 실패한다(등록과 동일).
    // PATCH 의미(보낸 필드만 변경)가 드러나도록 일부 필드 + 올바른 영업시간 예시를 명시한다.
    @Operation(summary = "식당 부분 수정", description = """
            보낸 필드만 변경합니다. address는 건물명·층까지 보존하는 화면 표시 주소이고,
            geocodingAddress는 Google 위치 확인에만 쓰는 별도 지정 주소입니다.
            geocodingAddress를 생략하거나 null로 보내면 address가 그대로일 때 기존 별도 지정 주소를 유지합니다.
            address만 변경하면 기존 별도 지정 주소를 삭제하고 새 address를 위치 확인 기준으로 사용합니다.
            이때 유효한 위치 확인 입력까지 달라진 경우에만 기존 좌표를 무효화하고 새 작업을 요청합니다.
            geocodingAddress의 공백 문자열은 별도 지정 주소를 명시적으로 삭제하고 현재 address를 사용한다는 뜻입니다.
            표시 주소를 바꾸면서 기존과 같은 위치 확인 기준을 geocodingAddress로 함께 보내면 좌표와 위치 상태를 유지합니다.
            200 응답은 식당 저장과 필요한 위치 작업 등록 성공을 뜻하며, Google 위치 확인 완료를 뜻하지 않습니다.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "일반 부분 수정(복붙 가능)", value = """
                    {
                      "name": "야키니쿠 리키마루 이케부쿠로 본점",
                      "summary": "리뉴얼한 이케부쿠로 야키니쿠 맛집",
                      "geocodingAddress": "東京都豊島区東池袋1-1-1",
                      "placeType": "cafe",
                      "menus": [
                        {
                          "menuId": 10,
                          "name": "특선 모둠 야키니쿠",
                          "description": "엄선한 부위 5종 모둠",
                          "imageKey": "restaurant-menus/a1b2c3-menu.jpg",
                          "priceCurrency": "JPY",
                          "priceAmount": 4500,
                          "main": true
                        }
                      ],
                      "businessHours": [
                        {"dayOfWeek": "MONDAY", "openTime": "11:30", "closeTime": "22:00", "closed": false},
                        {"dayOfWeek": "TUESDAY", "openTime": "11:30", "closeTime": "22:00", "closed": false},
                        {"dayOfWeek": "WEDNESDAY", "openTime": "11:30", "closeTime": "22:00", "closed": false},
                        {"dayOfWeek": "THURSDAY", "openTime": "11:30", "closeTime": "22:00", "closed": false},
                        {"dayOfWeek": "FRIDAY", "openTime": "11:30", "closeTime": "23:00", "closed": false},
                        {"dayOfWeek": "SATURDAY", "openTime": "11:30", "closeTime": "23:00", "closed": false},
                        {"dayOfWeek": "SUNDAY", "closed": true}
                      ]
                    }
                    """),
            @ExampleObject(
                    name = "표시 층만 변경하고 기존 위치 기준 유지",
                    description = "기존 geocodingAddress가 東京都豊島区東池袋1-1-1인 식당의 층만 바꾸는 예시입니다. "
                            + "같은 별도 지정 주소를 함께 보내므로 위치 확인 입력과 좌표·상태를 유지합니다.",
                    value = """
                    {
                      "address": "東京都豊島区東池袋1-1-1 架空ビル2F",
                      "geocodingAddress": "東京都豊島区東池袋1-1-1"
                    }
                    """),
            @ExampleObject(
                    name = "위치 확인 기준 변경",
                    description = "표시 주소와 별도 지정 주소의 위치 기준이 바뀌어 기존 좌표를 무효화하고 새 작업을 요청합니다.",
                    value = """
                    {
                      "address": "東京都豊島区東池袋2-2-2 新館1F",
                      "geocodingAddress": "東京都豊島区東池袋2-2-2"
                    }
                    """),
            @ExampleObject(
                    name = "표시 주소만 변경",
                    description = "geocodingAddress를 생략하면 기존 별도 지정 주소를 삭제하고 새 address를 위치 확인 기준으로 사용합니다. "
                            + "유효 입력까지 달라질 때만 좌표를 무효화합니다.",
                    value = """
                    {
                      "address": "東京都豊島区東池袋3-3-3 本館1F"
                    }
                    """),
            @ExampleObject(
                    name = "별도 지정 주소 삭제",
                    description = "공백 문자열은 저장된 geocodingAddress를 명시적으로 삭제하고 현재 address를 위치 확인 기준으로 사용합니다.",
                    value = """
                    {
                      "geocodingAddress": " "
                    }
                    """)
            }))
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MEDIA-001",
            message = "이미지 자산을 찾을 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-006",
            message = "현재 이미지 상태에서는 요청을 처리할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-007",
            message = "이미 사용 중이거나 사용이 끝난 이미지입니다")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MEDIA-008",
            message = "같은 이미지 자산을 중복해서 요청할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004",
            message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-009",
            message = "메뉴를 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-020",
            message = "이미 등록된 식당명입니다.")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-021",
            message = "이미 등록된 주소입니다.")
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"RESTAURANT_UPDATED"})
    @PatchMapping("/{restaurantId}")
    public SuccessResponse<AdminRestaurantResponse> update(
            @PathVariable Long restaurantId,
            @Valid @RequestBody UpdateRestaurantRequest request) {
        return SuccessResponse.of(AdminSuccessCode.RESTAURANT_UPDATED,
                adminRestaurantService.update(restaurantId, request));
    }

    /** 식당 삭제(soft delete) — 사용자 앱에서만 숨겨지고 기존 예약·리뷰는 유지된다. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"RESTAURANT_DELETED"})
    @DeleteMapping("/{restaurantId}")
    public SuccessResponse<Void> delete(@PathVariable Long restaurantId) {
        adminRestaurantService.delete(restaurantId);
        return SuccessResponse.of(AdminSuccessCode.RESTAURANT_DELETED, null);
    }

    /** 식당 정보 저장 결과와 별개인 지도 위치 처리 상태를 조회한다. */
    @GetMapping("/{restaurantId}/location")
    @Operation(summary = "식당 위치 처리 상태 조회", description = """
            식당 저장과 별도로 진행되는 최신 위치 확인 상태를 조회합니다.
            UNRESOLVED는 위치 행 없음, PENDING은 처리 중, READY는 위치 확인 완료,
            RETRY_WAIT는 자동 재시도 대기, REVIEW_REQUIRED는 관리자 확인 필요, FAILED는 자동 처리 중단입니다.
            READY여도 validUntil이 현재보다 늦어야 지도에서 사용할 수 있습니다.
            재시도 화면은 서버가 계산한 canRetry와 addressRevision을 함께 사용하고, 응답은 저장하지 말고 매번 최신 상태를 조회하세요.
            """)
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004", message = "식당을 찾을 수 없습니다.")
    public SuccessResponse<RestaurantLocationResponse> getLocation(@PathVariable Long restaurantId,
                                                                   HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, adminRestaurantService.getLocation(restaurantId));
    }

    /** 현재 주소 revision의 위치 확인을 재요청한다. PENDING은 기존 작업을 반환한다. */
    @PostMapping("/{restaurantId}/location/retry")
    @Operation(summary = "식당 위치 확인 재요청", description = """
            먼저 위치 상태 조회 API에서 최신 addressRevision을 읽어 expectedAddressRevision으로 보냅니다.
            같은 revision의 PENDING 작업이 있으면 새 작업을 만들지 않고 현재 상태를 반환하며,
            그 밖의 재시도 가능 상태는 PENDING 작업을 새로 요청합니다.
            주소 revision이 달라졌거나 이미 READY이면 409를 반환합니다.
            200 응답은 재요청 접수 성공을 뜻하며, Google 위치 확인 완료를 뜻하지 않습니다.
            """)
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004", message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-019", message = "현재 주소의 위치 상태를 다시 확인해주세요")
    public SuccessResponse<RestaurantLocationResponse> retryLocation(@PathVariable Long restaurantId,
            @Valid @RequestBody RetryRestaurantLocationRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK, adminRestaurantService.retryLocation(restaurantId, request));
    }

    /** 위치 상태별 관리자 검토 목록. 관리자 목록 표준인 0-based offset 페이지를 사용한다. */
    @GetMapping("/locations")
    @Operation(summary = "식당 위치 검토 목록 조회", description = """
            활성 식당을 locationStatus로 필터링해 restaurantId 오름차순으로 조회합니다.
            status 기본값은 REVIEW_REQUIRED이며 UNRESOLVED는 아직 위치 행이 없는 식당입니다.
            source는 GOOGLE_GEOCODING, GOOGLE_PLACES 또는 ADMIN으로 좁힐 때만 보내며, 승인 위치가 없는 행의 source는 null입니다.
            각 항목의 attempt와 failureCode는 위치의 현재 requestId와 일치하는 작업에서만 가져옵니다.
            READY는 validUntil이 현재보다 늦어야 실제 지도 위치로 사용할 수 있습니다.
            재시도는 canRetry가 true인 항목의 최신 addressRevision을 expectedAddressRevision으로 보내세요.
            page는 0부터 시작하며 같은 응답의 totalCount와 totalPages는 조회한 목록과 같은 DB snapshot 기준입니다.
            처리 중 상태가 바뀌면 페이지 사이의 항목과 총건수도 달라질 수 있습니다. 처리 후 현재 page를 다시 조회하세요.
            """)
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    public SuccessResponse<RestaurantLocationReviewListResponse> findLocationReviews(
            @Parameter(description = "위치 처리 상태. 생략하면 REVIEW_REQUIRED",
                    schema = @Schema(allowableValues = {"UNRESOLVED", "PENDING", "READY", "RETRY_WAIT",
                            "REVIEW_REQUIRED", "FAILED"}), example = "REVIEW_REQUIRED")
            @Pattern(regexp = "UNRESOLVED|PENDING|READY|RETRY_WAIT|REVIEW_REQUIRED|FAILED")
            @RequestParam(defaultValue = "REVIEW_REQUIRED") String status,
            @Parameter(description = "승인 위치 출처 필터. 생략하면 출처와 무관하게 조회",
                    schema = @Schema(allowableValues = {"GOOGLE_GEOCODING", "GOOGLE_PLACES", "ADMIN"}),
                    example = "GOOGLE_PLACES")
            @Pattern(regexp = "GOOGLE_GEOCODING|GOOGLE_PLACES|ADMIN")
            @RequestParam(required = false) String source,
            @Parameter(description = "0부터 시작하는 페이지 번호. page × size는 2,147,483,647 이하여야 함", example = "0")
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기(기본 20, 최대 100)", example = "20")
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminRestaurantService.findLocationReviews(status, source, page, size));
    }

    @PostMapping("/{restaurantId}/location/place-candidates")
    @Operation(summary = "식당 Places 후보 검색", description = """
            저장된 식당 현지명과 위치 확인 주소로 Google Places 후보를 새로 조회합니다.
            클라이언트가 임의 검색어나 Place ID, 좌표를 보내지 않으며 서버가 일본·도쿄·지도 서비스 범위를 검증합니다.
            외부 유료 호출이므로 화면 진입만으로 자동 호출하지 말고 관리자가 검색을 요청할 때 호출하세요.
            검색 결과가 없거나 범위를 통과한 후보가 없으면 200과 빈 candidates를 반환합니다.
            각 selectionToken은 검색 당시 restaurantId, addressRevision, requestId, Place ID에 묶여 10분 동안 유효합니다.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
            name = "현재 주소의 후보 검색", value = """
            {"expectedAddressRevision": 2}
            """)))
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004", message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.SERVICE_UNAVAILABLE, code = "RESTAURANT-024", message = "Places 위치 확인을 사용할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.TOO_MANY_REQUESTS, code = "RESTAURANT-025", message = "Places 호출 한도를 확인해주세요")
    @ApiErrorResponse(status = HttpStatus.BAD_GATEWAY, code = "RESTAURANT-026", message = "Places 응답을 확인할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-028", message = "현재 위치 상태를 다시 확인해주세요")
    public SuccessResponse<RestaurantPlacesSearchResponse> searchLocationPlaces(
            @PathVariable Long restaurantId,
            @Valid @RequestBody SearchRestaurantPlacesRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminRestaurantService.searchLocationPlaces(restaurantId, request));
    }

    @PostMapping("/{restaurantId}/location/place-selection")
    @Operation(summary = "식당 Places 후보 선택", description = """
            직전 후보 검색 응답의 expectedAddressRevision과 selectionToken으로 한 후보를 선택합니다.
            토큰을 수정했거나 만료됐으면 400, 주소·현재 작업·상태가 달라졌거나 PENDING이면 409입니다.
            선택 즉시 기존 승인 좌표를 제거하고 새 PLACE_DETAILS 작업을 PENDING으로 등록합니다.
            200 응답은 선택 저장과 작업 등록 성공을 뜻하며 Places 상세 확인 완료를 뜻하지 않습니다.
            locationStatus를 다시 조회해 READY와 validUntil을 확인하세요.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
            name = "검색 후보 선택", value = """
            {
              "expectedAddressRevision": 2,
              "selectionToken": "ZXlK...Q2Q"
            }
            """)))
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "RESTAURANT-027", message = "Places 선택 정보가 올바르지 않습니다")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004", message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.SERVICE_UNAVAILABLE, code = "RESTAURANT-024", message = "Places 위치 확인을 사용할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "RESTAURANT-028", message = "현재 위치 상태를 다시 확인해주세요")
    public SuccessResponse<RestaurantLocationResponse> selectLocationPlace(
            @PathVariable Long restaurantId,
            @Valid @RequestBody SelectRestaurantPlaceRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminRestaurantService.selectLocationPlace(restaurantId, request));
    }
}
