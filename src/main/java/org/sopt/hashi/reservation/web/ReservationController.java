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
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 예약 생성(일반·어디든)·취소·조회 API. */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** 일반 예약 생성 — amount는 기본 수수료(4,000) − usedPoint와 일치해야 한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = ReservationErrorCode.class,
            codes = {"RESTAURANT_NOT_FOUND", "USED_POINT_EXCEEDS_FEE", "AMOUNT_MISMATCH"})
    @ApiSuccess(value = ReservationSuccessCode.class, codes = {"RESERVATION_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<ReservationResponse> create(
            @Valid @RequestBody CreateReservationRequest request) {
        return SuccessResponse.of(ReservationSuccessCode.RESERVATION_CREATED,
                reservationService.create(request));
    }

    /** 어디든 예약 생성 — 미등록 식당의 식당명·주소를 직접 입력. amount 규칙은 일반 예약과 동일. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = ReservationErrorCode.class, codes = {"USED_POINT_EXCEEDS_FEE", "AMOUNT_MISMATCH"})
    @ApiSuccess(value = ReservationSuccessCode.class, codes = {"RESERVATION_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/anywhere")
    public SuccessResponse<ReservationResponse> createAnywhere(
            @Valid @RequestBody CreateAnywhereReservationRequest request) {
        return SuccessResponse.of(ReservationSuccessCode.RESERVATION_CREATED,
                reservationService.createAnywhere(request));
    }

    /** 내 예약 취소 — 진행중·확정 상태에서만 가능. 사용 포인트는 진행중 취소만 복원된다. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "CONFLICT"})
    @ApiException(value = ReservationErrorCode.class, codes = {"NOT_FOUND", "ALREADY_CANCELED", "CANNOT_CANCEL"})
    @ApiSuccess(value = ReservationSuccessCode.class, codes = {"RESERVATION_CANCELED"})
    @PostMapping("/{reservationId}/cancel")
    public SuccessResponse<ReservationResponse> cancel(@PathVariable Long reservationId) {
        return SuccessResponse.of(ReservationSuccessCode.RESERVATION_CANCELED,
                reservationService.cancel(reservationId));
    }

    /** 내 예약 목록(커서 페이지네이션) — status(IN_PROGRESS/UPCOMING/CANCELED) 생략 시 전체. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @GetMapping("/me")
    public SuccessResponse<ReservationListResponse> getMyReservations(
            @RequestParam(required = false) ReservationStatusFilter status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                reservationService.getMyReservations(cursor, size, status));
    }

    /** 내 예약 상세 — 본인 소유가 아니면 404. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED"})
    @ApiException(value = ReservationErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/{reservationId}")
    public SuccessResponse<ReservationDetailResponse> getMyReservation(
            @PathVariable Long reservationId) {
        return SuccessResponse.of(CommonSuccessCode.OK, reservationService.getMyReservation(reservationId));
    }
}
