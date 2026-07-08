package org.sopt.hashi.reservation.dto;

import java.util.Set;
import org.sopt.hashi.reservation.ReservationStatus;

/**
 * 내 예약 목록의 상태 탭 필터. 클라이언트 탭(진행중/방문예정/취소)을 예약 상태 집합으로 매핑한다.
 *
 * <p>{@link ReservationStatus#VISITED}(방문완료)는 어느 탭에도 없다 — 방문완료 예약 목록은 리뷰 작성 흐름과 얽혀
 * review 모듈이 {@code review → reservation} 방향으로 담당한다(§5-3 교차 조회, 순환 방지).
 */
public enum ReservationStatusFilter {

    /** 진행중 — 예약 요청~식당 컨택(확정 전). */
    IN_PROGRESS(Set.of(ReservationStatus.REQUESTED, ReservationStatus.CONTACTING)),

    /** 방문예정 — 식당이 확정한 예약. */
    UPCOMING(Set.of(ReservationStatus.CONFIRMED)),

    /** 취소 — 취소된 예약. */
    CANCELED(Set.of(ReservationStatus.CANCELED));

    private final Set<ReservationStatus> statuses;

    ReservationStatusFilter(Set<ReservationStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<ReservationStatus> statuses() {
        return statuses;
    }
}
