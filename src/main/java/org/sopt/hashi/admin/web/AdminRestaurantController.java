package org.sopt.hashi.admin.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.validation.Valid;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminRestaurantResponse;
import org.sopt.hashi.admin.dto.CreateRestaurantRequest;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.admin.service.AdminRestaurantService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiErrorResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
            name = "식당 등록 예시(복붙 가능)", value = """
            {
              "name": "야키니쿠 리키마루 이케부쿠로점",
              "localName": "焼肉力丸 池袋東口店",
              "summary": "이케부쿠로의 인기 야키니쿠 전문점",
              "description": "엄선된 고기와 다양한 코스를 제공합니다.",
              "address": "도쿄도 도시마구 히가시이케부쿠로 1-1-1",
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
            name = "부분 수정 예시(복붙 가능)", value = """
            {
              "name": "야키니쿠 리키마루 이케부쿠로 본점",
              "summary": "리뉴얼한 이케부쿠로 야키니쿠 맛집",
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
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004",
            message = "식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-009",
            message = "메뉴를 찾을 수 없습니다.")
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
}
