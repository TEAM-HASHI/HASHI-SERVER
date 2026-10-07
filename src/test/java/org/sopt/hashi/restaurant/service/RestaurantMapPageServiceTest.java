package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.internal.map.MapCursorCodec;
import org.sopt.hashi.restaurant.internal.map.MapQuerySession;
import org.sopt.hashi.restaurant.internal.map.MapSessionLimits;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.RedisMapSessionStore;
import org.sopt.hashi.shared.error.BusinessException;

class RestaurantMapPageServiceTest {
    @Test
    void 동시상한은_대기없이_거절하고_성공과_실패_모두_실행자리를_반환한다() throws Exception {
        var reader = mock(RestaurantMapPageReader.class);
        var store = mock(RedisMapSessionStore.class);
        var properties = new MapSessionProperties();
        properties.setEnabled(true);
        properties.setSigningKey(Base64.getEncoder().encodeToString(new byte[32]));
        var limits = new MapSessionLimits();
        limits.setConcurrentRequests(1);
        var service = new RestaurantMapPageService(mock(RestaurantMapService.class), reader, store,
                new MapCursorCodec(properties), Clock.systemUTC(), limits);
        var session = new MapQuerySession(1, UUID.randomUUID(), MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null), List.of(),
                Instant.now(), Instant.now().plusSeconds(1800));
        when(store.find(any())).thenReturn(session);
        when(store.touch(any(), any())).thenReturn(Instant.now().plusSeconds(300));
        var request = new RestaurantMapPageRequest(null, RestaurantMapSort.RECOMMEND, session.id().toString(), null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(reader.read(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
            return new RestaurantMapPageReader.Page(List.of(), false, 0);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.getPage(request, "caller-a"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.getPage(request, "caller-b"))
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED));
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).content()).isEmpty();
        }
        when(reader.read(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("synthetic reader failure"))
                .thenReturn(new RestaurantMapPageReader.Page(List.of(), false, 0));
        assertThatThrownBy(() -> service.getPage(request, "caller-a")).isInstanceOf(IllegalStateException.class);
        assertThat(service.getPage(request, "caller-a").content()).isEmpty();
    }
}
