package org.sopt.hashi.point.web;

import org.sopt.hashi.point.dto.PointBalanceResponse;
import org.sopt.hashi.point.service.PointService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 포인트 조회 API. */
@RestController
@RequestMapping("/api/v1/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    /** 내 잔여 포인트 조회 — 이력 없으면 0. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/me")
    public SuccessResponse<PointBalanceResponse> getMyBalance() {
        return SuccessResponse.of(CommonSuccessCode.OK, pointService.getMyBalance());
    }
}
