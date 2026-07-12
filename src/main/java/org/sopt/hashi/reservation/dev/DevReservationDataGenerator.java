package org.sopt.hashi.reservation.dev;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 더미 예약 생성 — local·dev 프로필에서만 빈이 등록된다(운영에는 존재 자체가 없음).
 * 리뷰 작성이 가능한 상태를 만들기 위해 CONFIRMED(결제 PAID 동반)를 거쳐 VISITED로 전이한
 * 방문 완료 예약을 생성한다. 식당 존재 검증은 호출 측(dev 모듈)이 생성 직후의 ID를 넘겨 보장한다.
 */
@Profile({"local", "dev"})
@Service
public class DevReservationDataGenerator {

    private static final int VISITED_WITHIN_DAYS = 30;

    private final ReservationRepository reservationRepository;

    public DevReservationDataGenerator(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /** 각 userId 명의로 해당 식당의 방문 완료(VISITED) 예약을 1건씩 생성하고 reservationId 목록을 반환한다. */
    @Transactional
    public List<Long> createVisitedReservations(Long restaurantId, List<Long> userIds) {
        List<Reservation> reservations = new ArrayList<>();
        for (Long userId : userIds) {
            Reservation reservation = Reservation.standard(
                    userId,
                    "더미예약자-" + userId,
                    restaurantId,
                    pastReservedAt(),
                    2, 0, 0,
                    null,
                    0, Reservation.BASE_FEE);
            // 방문 완료까지의 실제 여정과 같게 CONFIRMED(결제 PAID 동반)를 거쳐 VISITED로 전이한다.
            reservation.changeStatusByAdmin(ReservationStatus.CONFIRMED);
            reservation.changeStatusByAdmin(ReservationStatus.VISITED);
            reservations.add(reservation);
        }
        return reservationRepository.saveAll(reservations).stream().map(Reservation::getId).toList();
    }

    /** 최근 {@value #VISITED_WITHIN_DAYS}일 내 임의 날짜 19:00 — 방문 완료 예약이므로 과거 시각이다. */
    private LocalDateTime pastReservedAt() {
        int daysAgo = ThreadLocalRandom.current().nextInt(1, VISITED_WITHIN_DAYS + 1);
        return LocalDateTime.of(LocalDate.now().minusDays(daysAgo), LocalTime.of(19, 0));
    }
}
