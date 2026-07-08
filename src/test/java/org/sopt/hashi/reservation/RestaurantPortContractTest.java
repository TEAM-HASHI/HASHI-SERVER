package org.sopt.hashi.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;

/**
 * 이슈 #36 수용 기준 — 의존 모듈(reservation)이 restaurant 내부가 아닌 공개 계약(RestaurantPort·RestaurantInfo)만
 * import해 모킹으로 테스트할 수 있음을 고정한다. 모듈 컨텍스트 테스트(@ApplicationModuleTest + @MockitoBean)는
 * reservation 본체 개발 시 도입한다(CI에 DB·Redis가 아직 없음).
 */
class RestaurantPortContractTest {

    @Test
    void 의존_모듈은_RestaurantPort_모킹만으로_식당_존재_검증을_테스트할_수_있다() {
        RestaurantPort restaurantPort = mock(RestaurantPort.class);
        given(restaurantPort.existsById(anyLong())).willReturn(true);

        assertThat(restaurantPort.existsById(1L)).isTrue();
    }

    @Test
    void 의존_모듈은_RestaurantInfo로_식당_요약을_enrich할_수_있다() {
        RestaurantPort restaurantPort = mock(RestaurantPort.class);
        given(restaurantPort.findSummaryById(1L))
                .willReturn(Optional.of(new RestaurantInfo(
                        1L, "하시식당", "도쿄 신주쿠 1-1", "https://presigned.example/main.jpg")));

        Optional<RestaurantInfo> summary = restaurantPort.findSummaryById(1L);

        assertThat(summary).hasValueSatisfying(info -> {
            assertThat(info.id()).isEqualTo(1L);
            assertThat(info.name()).isEqualTo("하시식당");
        });
    }
}
