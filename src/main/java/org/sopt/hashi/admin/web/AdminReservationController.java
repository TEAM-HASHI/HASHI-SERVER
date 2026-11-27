package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminReservationListResponse;
import org.sopt.hashi.admin.dto.AdminReservationResponse;
import org.sopt.hashi.admin.dto.AdminReservationUserResponse;
import org.sopt.hashi.admin.dto.ChangeReservationStatusRequest;
import org.sopt.hashi.admin.dto.SetReservationAlternativesRequest;
import org.sopt.hashi.reservation.ReservationAlternativesInfo;
import org.sopt.hashi.admin.service.AdminReservationService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 어드민 예약 관리 API — 상태 변경·목록·예약자 조회. */
@RestController
@RequestMapping("/api/v1/admin/reservations")
public class AdminReservationController {

    private final AdminReservationService adminReservationService;

    public AdminReservationController(AdminReservationService adminReservationService) {
        this.adminReservationService = adminReservationService;
    }

    /** 예약 상태 변경 — 유상 예약은 입금 대기 후 입금을 확인하면 확정하고, 0원 예약은 바로 확정한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiResponse(responseCode = "409", description = "현재 예약 또는 결제 상태에서는 요청한 상태로 변경할 수 없습니다")
    @ApiSuccess(value = AdminSuccessCode.class, codes = {"RESERVATION_STATUS_CHANGED"})
    @PostMapping("/{reservationId}/status")
    public SuccessResponse<AdminReservationResponse> changeStatus(
            @PathVariable Long reservationId,
            @Valid @RequestBody ChangeReservationStatusRequest request) {
        return SuccessResponse.of(AdminSuccessCode.RESERVATION_STATUS_CHANGED,
                adminReservationService.changeStatus(reservationId, request.status()));
    }

    /** 자리를 확보하지 못한 예약을 실패 처리하고 확인된 대안 목록을 교체한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiResponse(responseCode = "409", description = "실패로 처리할 수 없는 예약입니다")
    @PutMapping("/{reservationId}/alternatives")
    public SuccessResponse<ReservationAlternativesInfo> setAlternatives(@PathVariable Long reservationId,
            @Valid @RequestBody SetReservationAlternativesRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, adminReservationService.setAlternatives(reservationId, request));
    }

    /** 등록한 대안과 이미 접수된 재예약 ID를 확인한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping("/{reservationId}/alternatives")
    public SuccessResponse<ReservationAlternativesInfo> getAlternatives(@PathVariable Long reservationId) {
        return SuccessResponse.of(CommonSuccessCode.OK, adminReservationService.getAlternatives(reservationId));
    }

    /** 예약 목록 조회 — offset 페이지네이션(최신순, page는 0부터). status 필터 선택. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping
    public SuccessResponse<AdminReservationListResponse> getReservations(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminReservationService.getReservations(status, page, size));
    }

    /** 예약자 정보 조회 — 예약 대행에 필요한 연락·식별 정보. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping("/{reservationId}/user")
    public SuccessResponse<AdminReservationUserResponse> getReserver(@PathVariable Long reservationId) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminReservationService.getReserver(reservationId));
    }
}
