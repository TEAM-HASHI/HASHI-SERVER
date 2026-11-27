package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.sopt.hashi.reservation.ReservationStatus;

/** 어드민 예약 상태 변경 요청 — 입금 대기를 시작하거나 입금 확인 후 확정한다. */
public record ChangeReservationStatusRequest(
        @Schema(description = "유상 예약은 AWAITING_PAYMENT를 거쳐 CONFIRMED로 확정합니다. 24시간 안내 기한이 지난 뒤에도 입금을 확인하면 확정할 수 있습니다. 결제 완료 건의 상태를 정정해도 PAID와 기존 입금 기한이 유지됩니다.", example = "CONFIRMED")
        @NotNull(message = "변경할 예약 상태는 필수입니다") ReservationStatus status) {
}
