package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminReservationListResponse;
import org.sopt.hashi.admin.dto.AdminReservationResponse;
import org.sopt.hashi.admin.dto.AdminReservationUserResponse;
import org.sopt.hashi.admin.dto.ChangeReservationStatusRequest;
import org.sopt.hashi.admin.service.AdminReservationService;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 예약 관리 컨트롤러 — 상태 변경·목록·예약자 조회. /api/v1/admin/** 경로라
 * ROLE_ADMIN 토큰만 접근할 수 있다(SecurityConfig). 예약 미존재(RESERVATION-001)·예약자 미존재
 * (RESERVATION-007) 에러는 reservation 모듈이 던진 것이 그대로 내려간다(코드 소유 모듈 원칙).
 */
@RestController
@RequestMapping("/api/v1/admin/reservations")
public class AdminReservationController {

    private final AdminReservationService adminReservationService;

    public AdminReservationController(AdminReservationService adminReservationService) {
        this.adminReservationService = adminReservationService;
    }

    /** 예약 상태 변경 — 자유 전이(되돌림 포함). CANCELED 진입 시 진행중 예약의 사용 포인트가 복원된다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @PostMapping("/{reservationId}/status")
    public SuccessResponse<AdminReservationResponse> changeStatus(
            @PathVariable Long reservationId,
            @Valid @RequestBody ChangeReservationStatusRequest request) {
        return SuccessResponse.of(AdminSuccessCode.RESERVATION_STATUS_CHANGED,
                adminReservationService.changeStatus(reservationId, request.status()));
    }

    /** 예약 목록 — 전체 사용자 대상 offset 페이지네이션(최신순, page는 0부터). status 필터는 선택. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping
    public SuccessResponse<AdminReservationListResponse> getReservations(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminReservationService.getReservations(status, page, size));
    }

    /** 예약자(예약을 만든 회원) 정보 조회 — 예약 대행에 필요한 연락·식별 정보를 내린다. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping("/{reservationId}/user")
    public SuccessResponse<AdminReservationUserResponse> getReserver(@PathVariable Long reservationId) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminReservationService.getReserver(reservationId));
    }
}
