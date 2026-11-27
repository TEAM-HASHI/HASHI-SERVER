package org.sopt.hashi.reservation.dto;

import org.sopt.hashi.reservation.ReservationAlternativesInfo;

public record ReservationAlternativesResponse(ReservationDetailResponse reservation,
                                             ReservationAlternativesInfo alternatives) {}
