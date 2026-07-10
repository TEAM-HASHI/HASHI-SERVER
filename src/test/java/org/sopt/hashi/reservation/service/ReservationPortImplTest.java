package org.sopt.hashi.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationPortImplTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationService reservationService;

    private ReservationPortImpl reservationPort;

    @BeforeEach
    void setUp() {
        reservationPort = new ReservationPortImpl(reservationRepository, reservationService);
    }

    @Test
    void 본인_예약의_리뷰용_정보를_조회한다() {
        Reservation reservation = standardReservation(7L);
        given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));

        ReservationReviewInfo result = reservationPort.getReviewInfoByIdAndUserId(100L, 7L);

        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.reservationType()).isEqualTo(ReservationType.STANDARD);
        assertThat(result.restaurantId()).isEqualTo(10L);
        assertThat(result.restaurantName()).isNull();
        assertThat(result.restaurantAddress()).isNull();
    }

    @Test
    void 어디든_예약의_직접_입력한_식당_정보를_매핑한다() {
        Reservation reservation = Reservation.anywhere(
                7L,
                "예약자",
                "긴자 미등록 식당",
                "도쿄도 주오구 긴자",
                LocalDateTime.of(2026, 7, 1, 18, 0),
                2,
                0,
                0,
                null,
                0L,
                4_000L
        );
        ReflectionTestUtils.setField(reservation, "id", 101L);
        given(reservationRepository.findById(101L)).willReturn(Optional.of(reservation));

        ReservationReviewInfo result = reservationPort.getReviewInfoByIdAndUserId(101L, 7L);

        assertThat(result.reservationType()).isEqualTo(ReservationType.ANYWHERE);
        assertThat(result.restaurantId()).isNull();
        assertThat(result.restaurantName()).isEqualTo("긴자 미등록 식당");
        assertThat(result.restaurantAddress()).isEqualTo("도쿄도 주오구 긴자");
    }

    @Test
    void 타인_예약은_존재하더라도_찾을_수_없음으로_응답한다() {
        Reservation reservation = standardReservation(99L);
        given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationPort.getReviewInfoByIdAndUserId(100L, 7L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.NOT_FOUND));
    }

    private Reservation standardReservation(Long userId) {
        Reservation reservation = Reservation.standard(
                userId,
                "예약자",
                10L,
                LocalDateTime.of(2026, 7, 1, 18, 0),
                2,
                0,
                0,
                null,
                0L,
                4_000L
        );
        ReflectionTestUtils.setField(reservation, "id", 100L);
        return reservation;
    }
}
