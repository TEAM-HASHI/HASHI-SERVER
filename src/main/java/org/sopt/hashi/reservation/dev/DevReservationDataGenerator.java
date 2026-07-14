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
 * 상태 전이는 실제 여정과 같게 수행한다 — VISITED는 CONFIRMED(결제 PAID 동반)를 거치고,
 * CANCELED는 진행중 취소(결제 취소 동반)를 재현한다. 식당 존재 검증은 호출 측(dev 모듈)이
 * 생성 직후의 ID를 넘겨 보장한다.
 */
@Profile({"local", "dev"})
@Service
public class DevReservationDataGenerator {

    private static final int VISITED_WITHIN_DAYS = 30;
    private static final int RESERVED_WITHIN_DAYS = 30;

    private final ReservationRepository reservationRepository;

    public DevReservationDataGenerator(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /** 각 userId 명의로 해당 식당의 방문 완료(VISITED) 예약을 1건씩 생성하고 reservationId 목록을 반환한다. */
    @Transactional
    public List<Long> createVisitedReservations(Long restaurantId, List<Long> userIds) {
        List<Reservation> reservations = new ArrayList<>();
        for (Long userId : userIds) {
            reservations.add(newStandardReservation(restaurantId, userId, ReservationStatus.VISITED));
        }
        return reservationRepository.saveAll(reservations).stream().map(Reservation::getId).toList();
    }

    /** 주어진 상태의 등록 식당(STANDARD) 더미 예약 1건을 생성하고 reservationId를 반환한다. */
    @Transactional
    public Long createReservation(Long restaurantId, Long userId, ReservationStatus status) {
        return reservationRepository.save(newStandardReservation(restaurantId, userId, status)).getId();
    }

    /** 주어진 상태의 미등록 식당(어디든·ANYWHERE) 더미 예약 1건을 생성하고 reservationId를 반환한다. */
    @Transactional
    public Long createAnywhereReservation(Long userId, ReservationStatus status) {
        Reservation reservation = Reservation.anywhere(
                userId,
                "더미예약자-" + userId,
                "더미어디든식당-" + userId,
                "도쿄도 시부야구 더미 1-2-3",
                reservedAtFor(status),
                2, 0, 0,
                null,
                0, Reservation.BASE_FEE);
        transitionTo(reservation, status);
        return reservationRepository.save(reservation).getId();
    }

    private Reservation newStandardReservation(Long restaurantId, Long userId, ReservationStatus status) {
        Reservation reservation = Reservation.standard(
                userId,
                "더미예약자-" + userId,
                restaurantId,
                reservedAtFor(status),
                2, 0, 0,
                null,
                0, Reservation.BASE_FEE);
        transitionTo(reservation, status);
        return reservation;
    }

    /** 실제 여정과 같은 경로로 목표 상태까지 전이한다. 취소는 방문 전에만 가능하므로 진행중 취소로 재현한다. */
    private void transitionTo(Reservation reservation, ReservationStatus status) {
        switch (status) {
            case REQUESTED -> {
            }
            case CONTACTING -> reservation.changeStatusByAdmin(ReservationStatus.CONTACTING);
            case CONFIRMED -> reservation.changeStatusByAdmin(ReservationStatus.CONFIRMED);
            case VISITED -> {
                reservation.changeStatusByAdmin(ReservationStatus.CONFIRMED);
                reservation.changeStatusByAdmin(ReservationStatus.VISITED);
            }
            case CANCELED -> reservation.cancel();
        }
    }

    /** 방문 완료는 과거, 그 외(진행중·확정·취소)는 방문 전이므로 미래 시각을 예약 일시로 쓴다. */
    private LocalDateTime reservedAtFor(ReservationStatus status) {
        return status == ReservationStatus.VISITED ? pastReservedAt() : futureReservedAt();
    }

    /** 최근 {@value #VISITED_WITHIN_DAYS}일 내 임의 날짜 19:00 — 방문 완료 예약이므로 과거 시각이다. */
    private LocalDateTime pastReservedAt() {
        int daysAgo = ThreadLocalRandom.current().nextInt(1, VISITED_WITHIN_DAYS + 1);
        return LocalDateTime.of(LocalDate.now().minusDays(daysAgo), LocalTime.of(19, 0));
    }

    /** 향후 {@value #RESERVED_WITHIN_DAYS}일 내 임의 날짜 19:00 — 방문 전 예약의 예정 시각이다. */
    private LocalDateTime futureReservedAt() {
        int daysLater = ThreadLocalRandom.current().nextInt(1, RESERVED_WITHIN_DAYS + 1);
        return LocalDateTime.of(LocalDate.now().plusDays(daysLater), LocalTime.of(19, 0));
    }
}
