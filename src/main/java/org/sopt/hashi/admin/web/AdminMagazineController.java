package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminMagazineResponse;
import org.sopt.hashi.admin.dto.CreateMagazineRequest;
import org.sopt.hashi.admin.dto.UpdateMagazineRequest;
import org.sopt.hashi.admin.service.AdminMagazineService;
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

/** 어드민 매거진 관리 API — 등록·수정·삭제. */
@RestController
@RequestMapping("/api/v1/admin/magazines")
public class AdminMagazineController {

    private final AdminMagazineService adminMagazineService;

    public AdminMagazineController(AdminMagazineService adminMagazineService) {
        this.adminMagazineService = adminMagazineService;
    }

    /**
     * 매거진 등록 — 슬롯별 legacy key 또는 READY public asset ID를 받는다.
     * 상세 화면 데이터(본문·카드뉴스·해시태그·연결 식당)는 선택이고 목록 순서가 노출 순서다.
     * 삭제된 식당은 연결할 수 없으며, 없는 식당과 같이 MAGAZINE-002로 거절한다.
     */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MEDIA-001",
            message = "이미지 자산을 찾을 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-006",
            message = "현재 이미지 상태에서는 요청을 처리할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-007",
            message = "이미 사용 중이거나 사용이 끝난 이미지입니다")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MEDIA-008",
            message = "같은 이미지 자산을 중복해서 요청할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MAGAZINE-002",
            message = "연결하려는 식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MAGAZINE-003",
            message = "같은 카드뉴스 이미지를 중복해서 사용할 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MAGAZINE-004",
            message = "같은 해시태그를 중복해서 사용할 수 없습니다.")
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"MAGAZINE_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<AdminMagazineResponse> create(
            @Valid @RequestBody CreateMagazineRequest request) {
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_CREATED,
                adminMagazineService.create(request));
    }

    /**
     * 매거진 부분 수정 — 보낸 필드만 변경된다.
     * 카드뉴스·해시태그·연결 식당은 보내면 전체 교체하며 빈 목록은 모두 지운다.
     * 카드뉴스에서 빠진 asset은 같은 트랜잭션에서 사용 종료 처리된다.
     * 삭제된 식당은 연결할 수 없으며, 없는 식당과 같이 MAGAZINE-002로 거절한다.
     */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MEDIA-001",
            message = "이미지 자산을 찾을 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-006",
            message = "현재 이미지 상태에서는 요청을 처리할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.CONFLICT, code = "MEDIA-007",
            message = "이미 사용 중이거나 사용이 끝난 이미지입니다")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MEDIA-008",
            message = "같은 이미지 자산을 중복해서 요청할 수 없습니다")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MAGAZINE-001",
            message = "매거진을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MAGAZINE-002",
            message = "연결하려는 식당을 찾을 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MAGAZINE-003",
            message = "같은 카드뉴스 이미지를 중복해서 사용할 수 없습니다.")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "MAGAZINE-004",
            message = "같은 해시태그를 중복해서 사용할 수 없습니다.")
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
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "MAGAZINE-001",
            message = "매거진을 찾을 수 없습니다.")
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"MAGAZINE_DELETED"})
    @DeleteMapping("/{magazineId}")
    public SuccessResponse<Void> delete(@PathVariable Long magazineId) {
        adminMagazineService.delete(magazineId);
        return SuccessResponse.of(AdminSuccessCode.MAGAZINE_DELETED, null);
    }
}
