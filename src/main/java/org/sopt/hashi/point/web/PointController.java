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

/**
 * 포인트(points) 리소스 컨트롤러. 사용자 본인의 잔액 조회를 다룬다(사용자는 인증 컨텍스트에서 판단).
 * 적립·차감·복원은 API가 아니라 각 도메인(예약·리뷰)이 PointPort로 호출한다.
 */
@RestController
@RequestMapping("/api/v1/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    /** 내 잔여 포인트 조회. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/me")
    public SuccessResponse<PointBalanceResponse> getMyBalance() {
        return SuccessResponse.of(CommonSuccessCode.OK, pointService.getMyBalance());
    }
}
