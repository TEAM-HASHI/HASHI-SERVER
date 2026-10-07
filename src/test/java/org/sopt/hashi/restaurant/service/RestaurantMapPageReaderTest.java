package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.internal.map.MapQuerySession;

class RestaurantMapPageReaderTest {
    @Test
    void 후보620개여도_10개와_lookahead를_찾으면_첫묶음에서_멈춘다() {
        check(0, 1, 10);
    }

    @Test
    void 삭제된_70개를_건너뛰고_lookahead는_다음_cursor에_남긴다() {
        check(70, 3, 80);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void check(int deleted, int batches, int nextPosition) {
        var maps = mock(RestaurantMapService.class);
        var restaurants = mock(RestaurantService.class);
        var candidates = IntStream.rangeClosed(1, 620).mapToObj(id ->
                new RestaurantMapCandidate((long) id, BigDecimal.ZERO, 0)).toList();
        var criteria = MapSearchCriteria.of(MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null);
        var session = new MapQuerySession(1, UUID.randomUUID(), criteria, candidates,
                Instant.now(), Instant.now().plusSeconds(1800));
        when(maps.findMatchingCandidates(any(), any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(1);
            assertThat(ids).hasSizeLessThanOrEqualTo(32);
            return candidates.stream().filter(candidate -> ids.contains(candidate.restaurantId())
                    && candidate.restaurantId() > deleted).toList();
        });
        when(maps.findActiveMapInfos(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new RestaurantMapInfo(id, "fixture", "restaurant", "sushi",
                    new RestaurantMapInfo.LocationInfo(BigDecimal.ZERO, BigDecimal.ZERO, Instant.now().plusSeconds(60))))
                    .toList();
        });
        when(restaurants.findMapCards(any(), any())).thenReturn(List.of());
        var page = new RestaurantMapPageReader(maps, restaurants, Clock.systemUTC())
                .read(session, RestaurantMapSort.RECOMMEND, 0);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextPosition()).isEqualTo(nextPosition);
        verify(maps, times(batches)).findMatchingCandidates(any(), any());
        var selected = ArgumentCaptor.forClass(List.class);
        verify(restaurants).findMapCards(selected.capture(), any());
        assertThat((List<RestaurantMapCandidate>) selected.getValue()).hasSize(10)
                .extracting(RestaurantMapCandidate::restaurantId)
                .containsExactlyElementsOf(IntStream.rangeClosed(deleted + 1, deleted + 10)
                        .mapToObj(id -> (long) id).toList());
    }
}
