package org.sopt.hashi.magazine.web;

import jakarta.validation.constraints.Positive;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.code.MagazineSuccessCode;
import org.sopt.hashi.magazine.dto.MagazineLikeResponse;
import org.sopt.hashi.magazine.dto.MagazineLikeResult;
import org.sopt.hashi.magazine.service.MagazineLikeService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 매거진 좋아요 API — 회원 전용(ROLE_USER). 등록·취소 모두 멱등이며, 실제 상태 변화 여부는 성공 코드로 구분한다. */
@Validated
@RestController
@RequestMapping("/api/v1/magazines/{magazineId}/likes")
public class MagazineLikeController {

    private final MagazineLikeService magazineLikeService;

    public MagazineLikeController(MagazineLikeService magazineLikeService) {
        this.magazineLikeService = magazineLikeService;
    }

    /** 좋아요 등록 — 이미 좋아요 상태면 LIKE_ALREADY_CREATED 코드로 현재 상태·수를 그대로 돌려준다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = MagazineErrorCode.class, codes = {"NOT_FOUND"})
    @ApiSuccess(value = MagazineSuccessCode.class, codes = {"LIKE_CREATED", "LIKE_ALREADY_CREATED"})
    @PostMapping
    public SuccessResponse<MagazineLikeResponse> like(@Positive @PathVariable Long magazineId) {
        MagazineLikeResult result = magazineLikeService.like(magazineId);
        return SuccessResponse.of(result.code(), result.response());
    }

    /** 좋아요 취소 — 좋아요 상태가 아니면 LIKE_ALREADY_DELETED 코드로 현재 상태·수를 그대로 돌려준다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = MagazineErrorCode.class, codes = {"NOT_FOUND"})
    @ApiSuccess(value = MagazineSuccessCode.class, codes = {"LIKE_DELETED", "LIKE_ALREADY_DELETED"})
    @DeleteMapping
    public SuccessResponse<MagazineLikeResponse> unlike(@Positive @PathVariable Long magazineId) {
        MagazineLikeResult result = magazineLikeService.unlike(magazineId);
        return SuccessResponse.of(result.code(), result.response());
    }
}
