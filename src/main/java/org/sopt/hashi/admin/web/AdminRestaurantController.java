package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminRestaurantResponse;
import org.sopt.hashi.admin.dto.CreateRestaurantRequest;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.admin.service.AdminRestaurantService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
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

    /** 식당 등록 — 썸네일·이미지·메뉴 사진은 presigned URL로 업로드를 마친 S3 키로 받는다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"RESTAURANT_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<AdminRestaurantResponse> create(
            @Valid @RequestBody CreateRestaurantRequest request) {
        return SuccessResponse.of(AdminSuccessCode.RESTAURANT_CREATED,
                adminRestaurantService.create(request));
    }

    /** 식당 부분 수정 — 보낸 필드만 변경, 컬렉션(이미지·메뉴·큐레이션)은 전체 교체. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
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
