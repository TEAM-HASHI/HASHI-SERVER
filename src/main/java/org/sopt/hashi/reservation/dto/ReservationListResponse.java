package org.sopt.hashi.reservation.dto;

import java.util.List;

/**
 * 내 예약 목록 응답(커서 페이지네이션). nextCursor는 다음 페이지 요청에 그대로 전달하며,
 * hasNext가 false면 마지막 페이지라 nextCursor는 null이다.
 */
public record ReservationListResponse(
        List<ReservationResponse> reservations,
        Long nextCursor,
        boolean hasNext) {
}
