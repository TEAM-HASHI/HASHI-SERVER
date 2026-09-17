package org.sopt.hashi.magazine.web;

import jakarta.validation.constraints.Positive;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse;
import org.sopt.hashi.magazine.service.MagazineService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 매거진 조회 API. */
@Validated
@RestController
@RequestMapping("/api/v1/magazines")
public class MagazineController {

    private final MagazineService magazineService;

    public MagazineController(MagazineService magazineService) {
        this.magazineService = magazineService;
    }

    /** 매거진 배너 목록 조회 — 최신 5개. 탭 시 instagramRedirectUrl로 이동. */
    @GetMapping("/banners")
    public SuccessResponse<MagazineBannerListResponse> getBanners() {
        return SuccessResponse.of(CommonSuccessCode.OK, magazineService.getBanners());
    }

    /** 매거진 목록 조회 — 최신순 커서 페이지네이션. */
    @GetMapping
    public SuccessResponse<MagazineListResponse> getMagazines(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return SuccessResponse.of(CommonSuccessCode.OK, magazineService.getMagazines(cursor, size));
    }

    /** 매거진 상세 조회(MAG-002) — 비로그인 허용. 로그인 회원이면 liked에 내 좋아요 여부가 실린다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = MagazineErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/{magazineId}")
    public SuccessResponse<MagazineDetailResponse> getDetail(@Positive @PathVariable Long magazineId) {
        return SuccessResponse.of(CommonSuccessCode.OK, magazineService.getDetail(magazineId));
    }
}
