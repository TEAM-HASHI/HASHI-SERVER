package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminMagazineResponse;
import org.sopt.hashi.admin.dto.CreateMagazineRequest;
import org.sopt.hashi.admin.dto.UpdateMagazineRequest;
import org.sopt.hashi.admin.service.AdminMagazineService;
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

/** 어드민 매거진 관리 API — 등록·수정·삭제. */
@RestController
@RequestMapping("/api/v1/admin/magazines")
public class AdminMagazineController {

    private final AdminMagazineService adminMagazineService;

    public AdminMagazineController(AdminMagazineService adminMagazineService) {
        this.adminMagazineService = adminMagazineService;
    }

    /** 매거진 등록 — bannerKey는 presigned URL로 업로드를 마친 S3 키. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"MAGAZINE_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<AdminMagazineResponse> create(
            @Valid @RequestBody CreateMagazineRequest request) {
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_CREATED,
                adminMagazineService.create(request));
    }

    /** 매거진 부분 수정 — 보낸 필드만 변경된다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"MAGAZINE_UPDATED"})
    @PatchMapping("/{magazineId}")
    public SuccessResponse<AdminMagazineResponse> update(
            @PathVariable Long magazineId,
            @Valid @RequestBody UpdateMagazineRequest request) {
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_UPDATED,
                adminMagazineService.update(magazineId, request));
    }

    /** 매거진 삭제. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"MAGAZINE_DELETED"})
    @DeleteMapping("/{magazineId}")
    public SuccessResponse<Void> delete(@PathVariable Long magazineId) {
        adminMagazineService.delete(magazineId);
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_DELETED, null);
    }
}
