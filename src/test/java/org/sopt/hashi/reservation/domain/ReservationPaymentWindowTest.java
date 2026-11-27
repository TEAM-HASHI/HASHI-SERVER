package org.sopt.hashi.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sopt.hashi.reservation.ReservationStatus.AWAITING_PAYMENT;
import static org.sopt.hashi.reservation.ReservationStatus.CONFIRMED;
import static org.sopt.hashi.reservation.ReservationStatus.CONTACTING;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.reservation.PaymentStatus;
import org.sopt.hashi.reservation.dto.ReservationDetailResponse;
import org.sopt.hashi.reservation.dto.ReservationResponse;
import org.sopt.hashi.shared.error.BusinessException;

class ReservationPaymentWindowTest {

    private static final Instant START = Instant.parse("2026-11-27T01:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(86_400);

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1, 86_400})
    void 안내_기한_전후_어느_때나_입금을_확인해_확정할_수_있다(long secondsFromDeadline) {
        Reservation booking = standard(2000);
        booking.changeStatusByAdmin(AWAITING_PAYMENT, START);
        booking.changeStatusByAdmin(AWAITING_PAYMENT, START.plusSeconds(600));

        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(booking.refundableOnCancel()).isTrue();
        assertThat(ReservationResponse.of(booking, null, null, null).paymentWaitStartedAt()).isEqualTo(START);
        assertThat(ReservationDetailResponse.of(booking, null, null, null, null).paymentDeadline()).isEqualTo(DEADLINE);
        Instant confirmationTime = DEADLINE.plusSeconds(secondsFromDeadline);
        booking.changeStatusByAdmin(CONFIRMED, confirmationTime);
        booking.changeStatusByAdmin(CONFIRMED, confirmationTime.plusSeconds(1));
        assertThat(booking.getReservationStatus()).isEqualTo(CONFIRMED);
        assertThat(booking.getPaymentDeadline()).isEqualTo(DEADLINE);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 유상_예약은_대기를_생략하거나_기한을_되돌릴_수_없다() {
        Reservation booking = standard(0);
        assertThatThrownBy(() -> booking.changeStatusByAdmin(CONFIRMED, START))
                .isInstanceOf(BusinessException.class);
        booking.changeStatusByAdmin(AWAITING_PAYMENT, START);
        assertThatThrownBy(() -> booking.changeStatusByAdmin(CONTACTING, DEADLINE))
                .isInstanceOf(BusinessException.class);
        booking.cancel();
        assertThatThrownBy(() -> booking.changeStatusByAdmin(CONFIRMED, DEADLINE))
                .isInstanceOf(BusinessException.class);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void 전액_포인트_예약은_입금_기한을_만들지_않고_관리자가_확정한다() {
        Reservation booking = standard(Reservation.BASE_FEE);
        assertThatThrownBy(() -> booking.changeStatusByAdmin(AWAITING_PAYMENT, START))
                .isInstanceOf(BusinessException.class);
        booking.changeStatusByAdmin(CONFIRMED, START);
        assertThat(booking.getPaymentDeadline()).isNull();
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    private Reservation standard(long points) {
        return Reservation.standard(1L, "예약자", 2L, LocalDateTime.of(2027, 1, 1, 12, 0),
                1, 0, 0, null, points, Reservation.BASE_FEE - points);
    }
}
