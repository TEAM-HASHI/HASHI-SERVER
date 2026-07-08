package org.sopt.hashi.reservation.web;

import jakarta.validation.Valid;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.code.ReservationSuccessCode;
import org.sopt.hashi.reservation.dto.CreateAnywhereReservationRequest;
import org.sopt.hashi.reservation.dto.CreateReservationRequest;
import org.sopt.hashi.reservation.dto.ReservationDetailResponse;
import org.sopt.hashi.reservation.dto.ReservationListResponse;
import org.sopt.hashi.reservation.dto.ReservationResponse;
import org.sopt.hashi.reservation.dto.ReservationStatusFilter;
import org.sopt.hashi.reservation.service.ReservationService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약(reservations) 리소스 컨트롤러. 사용자 본인의 예약 생성(일반·어디든)·조회를 다룬다
 * (예약자는 인증 컨텍스트에서 판단). 취소·상태 전이는 별도 이슈 범위.
 */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** 일반 예약 — 등록된 식당(restaurantId)에 대한 예약. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = ReservationErrorCode.class, codes = {"RESTAURANT_NOT_FOUND"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<ReservationResponse> create(
            @Valid @RequestBody CreateReservationRequest request) {
        return SuccessResponse.of(ReservationSuccessCode.RESERVATION_CREATED,
                reservationService.create(request));
    }

    /** 어디든 예약 — 미등록 식당의 식당명·주소를 직접 받는 예약. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/anywhere")
    public SuccessResponse<ReservationResponse> createAnywhere(
            @Valid @RequestBody CreateAnywhereReservationRequest request) {
        return SuccessResponse.of(ReservationSuccessCode.RESERVATION_CREATED,
                reservationService.createAnywhere(request));
    }

    /** 내 예약 목록(커서 페이지네이션). status 탭(진행중/방문예정/취소)으로 거를 수 있고, 없으면 전체. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/me")
    public SuccessResponse<ReservationListResponse> getMyReservations(
            @RequestParam(required = false) ReservationStatusFilter status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                reservationService.getMyReservations(cursor, size, status));
    }

    /** 내 예약 상세(본인 소유만). */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = ReservationErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/{reservationId}")
    public SuccessResponse<ReservationDetailResponse> getMyReservation(
            @PathVariable Long reservationId) {
        return SuccessResponse.of(CommonSuccessCode.OK, reservationService.getMyReservation(reservationId));
    }
}
