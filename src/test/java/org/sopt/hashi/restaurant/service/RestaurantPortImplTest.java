package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 스텁의 비관적 기본값(항상 "존재하지 않음")을 고정한다 — 낙관적 값(true)으로 바뀌면 존재하지 않는 식당의
 * 예약·리뷰가 가짜 성공으로 통과하므로, 변경 시 이 테스트가 깨져 의도적 결정임을 강제한다.
 * restaurant 도메인 본체 구현 시 실제 조회 기반 테스트로 교체한다.
 */
class RestaurantPortImplTest {

    private final RestaurantPortImpl stub = new RestaurantPortImpl();

    @Test
    void 스텁은_모든_식당을_존재하지_않는다고_판정한다() {
        assertThat(stub.existsById(1L)).isFalse();
        assertThat(stub.existsById(null)).isFalse();
    }

    @Test
    void 스텁은_식당_요약을_항상_빈_값으로_반환한다() {
        assertThat(stub.findSummaryById(1L)).isEmpty();
        assertThat(stub.findSummaryById(null)).isEmpty();
    }
}
