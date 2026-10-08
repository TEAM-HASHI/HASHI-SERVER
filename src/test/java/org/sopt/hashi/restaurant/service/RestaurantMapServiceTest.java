package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.RestaurantMapQueryRepository;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.dao.DataAccessResourceFailureException;

class RestaurantMapServiceTest {

    private final RestaurantMapQueryRepository repository = mock(RestaurantMapQueryRepository.class);
    private final RestaurantMapService service = new RestaurantMapService(repository, new MapQueryProperties(),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void 빈_ID는_쿼리_없이_반환하고_잘못된_ID는_거절한다() {
        assertThat(service.findActiveMapInfos(null)).isEmpty();
        assertThat(service.findActiveMapInfos(List.of())).isEmpty();
        assertThatThrownBy(() -> service.findActiveMapInfos(List.of(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findActiveMapInfos(Arrays.asList(1L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 내부_두번째_batch_실패는_전체_실패이며_첫_batch를_성공처럼_반환하지_않는다() {
        given(repository.findActiveMapInfos(any(), any()))
                .willReturn(List.of(new RestaurantMapInfo(1L, "fixture", "cafe", "etc", null)))
                .willThrow(new DataAccessResourceFailureException("synthetic failure"));
        assertThatThrownBy(() -> service.findActiveMapInfos(LongStream.rangeClosed(1, 501).boxed().toList()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_QUERY_UNAVAILABLE));
    }

    @Test
    void 지도_설정이_없어도_Port는_삭제제외와_순서_중복제거_계약으로_조회한다() {
        given(repository.findActiveMapInfos(any(), any())).willReturn(List.of(
                new RestaurantMapInfo(1L, "first", "restaurant", "sushi", null),
                new RestaurantMapInfo(2L, "second", "cafe", "etc", null)));
        assertThat(service.findActiveMapInfos(List.of(2L, 3L, 1L, 2L)))
                .extracting(RestaurantMapInfo::restaurantId).containsExactly(2L, 1L);
    }
}
