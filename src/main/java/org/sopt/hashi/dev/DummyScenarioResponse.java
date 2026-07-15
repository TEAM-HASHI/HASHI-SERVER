package org.sopt.hashi.dev;

import java.util.List;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;

/**
 * 더미 시나리오 생성 결과 — 생성된 각 도메인의 식별자 목록과, 바로 쓸 수 있는 대표 더미 유저 토큰.
 * sampleUser의 accessToken을 Authorize에 붙이면 그 유저의 예약·리뷰 조회가 곧장 동작하고,
 * sampleUserReservations로 어떤 예약이 어떤 상태 케이스인지 확인할 수 있다.
 */
public record DummyScenarioResponse(
        Long restaurantId,
        List<Long> userIds,
        List<Long> reservationIds,
        List<Long> reviewIds,
        List<SampleReservation> sampleUserReservations,
        SampleUser sampleUser) {

    /** 대표 더미 유저 — 생성된 첫 번째 유저와 그 명의의 USER 액세스 토큰. */
    public record SampleUser(Long userId, String accessToken) {
    }

    /**
     * 대표 더미 유저 명의로 생성된 예약 1건의 상태 케이스.
     * reviewState는 방문 완료된 STANDARD 예약만 UNREVIEWED/REVIEWED/DELETED로 채우고 그 외는 null이다.
     */
    public record SampleReservation(
            Long reservationId,
            ReservationType reservationType,
            ReservationStatus reservationStatus,
            String reviewState,
            Long reviewId) {
    }
}
