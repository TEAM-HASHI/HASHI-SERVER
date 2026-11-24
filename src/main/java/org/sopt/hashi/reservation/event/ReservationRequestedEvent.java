package org.sopt.hashi.reservation.event;

import org.sopt.hashi.reservation.ReservationType;

public record ReservationRequestedEvent(long reservationId, ReservationType reservationType) {
}
