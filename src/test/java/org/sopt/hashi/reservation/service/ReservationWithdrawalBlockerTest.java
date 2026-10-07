package org.sopt.hashi.reservation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.shared.error.BusinessException;

@ExtendWith(MockitoExtension.class)
class ReservationWithdrawalBlockerTest {

    private static final Set<ReservationStatus> UNFINISHED = Set.of(
            ReservationStatus.REQUESTED, ReservationStatus.CONTACTING, ReservationStatus.CONFIRMED);

    @Mock
    private ReservationRepository reservationRepository;

    @Test
    void 방문_완료나_취소가_아닌_예약이_있으면_탈퇴를_막는다() {
        given(reservationRepository.existsByUserIdAndReservationStatusIn(7L, UNFINISHED)).willReturn(true);

        assertThatThrownBy(() -> new ReservationWithdrawalBlocker(reservationRepository).validateWithdrawable(7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.UNFINISHED_RESERVATION_EXISTS);
    }

    @Test
    void 모든_예약이_방문_완료나_취소로_끝났으면_탈퇴할_수_있다() {
        given(reservationRepository.existsByUserIdAndReservationStatusIn(7L, UNFINISHED)).willReturn(false);

        assertThatCode(() -> new ReservationWithdrawalBlocker(reservationRepository).validateWithdrawable(7L))
                .doesNotThrowAnyException();
    }
}
