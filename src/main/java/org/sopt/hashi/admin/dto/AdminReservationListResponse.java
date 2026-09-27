package org.sopt.hashi.admin.dto;

import java.util.List;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.springframework.data.domain.Page;

/** 어드민 예약 목록 응답 — offset 페이지네이션 메타(page·size·totalCount·totalPages) 포함. */
public record AdminReservationListResponse(
        List<AdminReservationResponse> reservations,
        int page,
        int size,
        long totalCount,
        int totalPages) {

    public static AdminReservationListResponse from(
            Page<AdminReservationInfo> page,
            List<AdminReservationResponse> reservations
    ) {
        return new AdminReservationListResponse(
                List.copyOf(reservations),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
