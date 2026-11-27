package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 빈 목록도 허용한다. 식당 ID는 어드민에 입력한 순서로 표시한다. */
public record SetReservationAlternativesRequest(
        @NotNull @Size(max = 4) List<@NotNull LocalDateTime> times,
        @NotNull @Size(max = 3) List<@NotNull @Positive Long> restaurantIds) {}
