package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** UNRESOLVED(위치 행 없음)의 revision은 0이다. */
public record RetryRestaurantLocationRequest(@NotNull @PositiveOrZero Long expectedAddressRevision) {
}
