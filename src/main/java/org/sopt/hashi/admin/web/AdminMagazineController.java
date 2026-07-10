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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 매거진 관리 컨트롤러 — 등록·수정·삭제. /api/v1/admin/** 경로라 ROLE_ADMIN 토큰만
 * 접근할 수 있다(SecurityConfig). 매거진 미존재(MAGAZINE-001) 에러는 magazine 모듈이 던진 것이
 * 그대로 내려간다(코드 소유 모듈 원칙).
 */
@RestController
@RequestMapping("/api/v1/admin/magazines")
public class AdminMagazineController {

    private final AdminMagazineService adminMagazineService;

    public AdminMagazineController(AdminMagazineService adminMagazineService) {
        this.adminMagazineService = adminMagazineService;
    }

    /** 매거진 등록 — 배너 이미지는 presigned URL로 업로드 완료된 S3 키(bannerKey)를 받는다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<AdminMagazineResponse> create(
            @Valid @RequestBody CreateMagazineRequest request) {
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_CREATED,
                adminMagazineService.create(request));
    }

    /** 매거진 수정 — 부분 수정(PATCH), 보낸 필드만 변경된다. 미존재 시 MAGAZINE-001이 내려간다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @PatchMapping("/{magazineId}")
    public SuccessResponse<AdminMagazineResponse> update(
            @PathVariable Long magazineId,
            @Valid @RequestBody UpdateMagazineRequest request) {
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_UPDATED,
                adminMagazineService.update(magazineId, request));
    }

    /** 매거진 삭제 — 미존재 시 MAGAZINE-001이 내려간다. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @DeleteMapping("/{magazineId}")
    public SuccessResponse<Void> delete(@PathVariable Long magazineId) {
        adminMagazineService.delete(magazineId);
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_DELETED, null);
    }
}
