package org.sopt.hashi.magazine.web;

import org.sopt.hashi.magazine.dto.MagazineBannerListResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse;
import org.sopt.hashi.magazine.service.MagazineService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/magazines")
public class MagazineController {

    private final MagazineService magazineService;

    public MagazineController(MagazineService magazineService) {
        this.magazineService = magazineService;
    }

    /** 매거진 배너 목록 — 최신 5개. 배너 클릭 시 클라이언트가 instagramRedirectUrl로 이동시킨다. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/banners")
    public SuccessResponse<MagazineBannerListResponse> getBanners() {
        return SuccessResponse.of(CommonSuccessCode.OK, magazineService.getBanners());
    }

    /** 매거진 목록(최신순 커서 페이지네이션). ⚠️ 필터링은 MVP 이후 추가 예정. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping
    public SuccessResponse<MagazineListResponse> getMagazines(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return SuccessResponse.of(CommonSuccessCode.OK, magazineService.getMagazines(cursor, size));
    }
}
