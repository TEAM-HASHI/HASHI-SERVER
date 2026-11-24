package org.sopt.hashi.reservation.event;

import java.time.LocalDateTime;
import org.sopt.hashi.reservation.ReservationType;

public record ReservationRequestedEvent(
        long reservationId,
        ReservationType reservationType,
        String maskedReserverName,
        String restaurantName,
        LocalDateTime reservedAt,
        int adultCount,
        int teenCount,
        int childCount
) {

    @Override
    public String toString() {
        return "ReservationRequestedEvent[reservationId=%d, reservationType=%s]"
                .formatted(reservationId, reservationType);
    }
}
