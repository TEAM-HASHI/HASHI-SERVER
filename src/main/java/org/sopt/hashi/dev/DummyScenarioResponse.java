package org.sopt.hashi.dev;

import java.util.List;

/**
 * 더미 시나리오 생성 결과 — 생성된 각 도메인의 식별자 목록과, 바로 쓸 수 있는 대표 더미 유저 토큰.
 * sampleUser의 accessToken을 Authorize에 붙이면 그 유저의 예약·리뷰 조회가 곧장 동작한다.
 */
public record DummyScenarioResponse(
        Long restaurantId,
        List<Long> userIds,
        List<Long> reservationIds,
        List<Long> reviewIds,
        SampleUser sampleUser) {

    /** 대표 더미 유저 — 생성된 첫 번째 유저와 그 명의의 USER 액세스 토큰. */
    public record SampleUser(Long userId, String accessToken) {
    }
}
