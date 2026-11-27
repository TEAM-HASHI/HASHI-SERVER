package org.sopt.hashi.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import org.sopt.hashi.reservation.domain.Reservation;

public record RebookReservationRequest(@NotNull LocalDateTime reservedAt,
                                       @NotNull @Min(0) @Max(Reservation.BASE_FEE) Long usedPoint) {}
