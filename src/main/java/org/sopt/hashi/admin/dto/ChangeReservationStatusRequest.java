package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.sopt.hashi.reservation.ReservationStatus;

/** 어드민 예약 상태 변경 요청 — 입금 대기를 시작하거나 입금 확인 후 확정한다. */
public record ChangeReservationStatusRequest(
        @Schema(description = "변경할 목표 상태", example = "CONFIRMED")
        @NotNull(message = "변경할 예약 상태는 필수입니다") ReservationStatus status) {
}
