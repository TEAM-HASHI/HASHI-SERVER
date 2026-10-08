package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.internal.map.MapCursorCodec;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics;
import org.sopt.hashi.restaurant.internal.map.MapQuerySession;
import org.sopt.hashi.restaurant.internal.map.MapSessionId;
import org.sopt.hashi.restaurant.internal.map.MapSessionLimits;
import org.sopt.hashi.restaurant.internal.map.MapSessionProperties;
import org.sopt.hashi.restaurant.internal.map.RedisMapSessionStore;
import org.sopt.hashi.shared.error.BusinessException;

class RestaurantMapPageServiceTest {
    @Test
    void 신규조회_성공은_다섯_처리단계를_같은_operation으로_기록한다() {
        var mapService = mock(RestaurantMapService.class);
        var reader = mock(RestaurantMapPageReader.class);
        var store = mock(RedisMapSessionStore.class);
        var properties = enabledProperties();
        var registry = new SimpleMeterRegistry();
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant rankingAsOf = startedAt.plusSeconds(1);
        Instant expiresAt = startedAt.plusSeconds(300);
        var sessionId = new MapSessionId(UUID.randomUUID());
        when(store.admit(any(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(startedAt);
        when(mapService.findCandidates(any(), anyInt()))
                .thenReturn(new RestaurantMapService.CandidateSnapshot(List.of(), rankingAsOf));
        when(store.save(any())).thenReturn(sessionId);
        when(reader.read(any(), any(), anyInt()))
                .thenReturn(new RestaurantMapPageReader.Page(List.of(), false, 0));
        when(store.touch(any(), any())).thenReturn(expiresAt);
        var service = new RestaurantMapPageService(mapService, reader, store,
                new MapCursorCodec(properties), new MapSessionLimits(), new MapCapacityMetrics(registry));
        var request = new RestaurantMapPageRequest(MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null),
                RestaurantMapSort.RECOMMEND, null, null);

        assertThat(service.getPage(request, "caller").querySessionId()).isEqualTo(sessionId.value());

        assertThat(List.of("admit", "candidates", "save", "read_page", "touch"))
                .allSatisfy(stage -> assertThat(registry.get("hashi.restaurant.map.stage.duration")
                        .tags("operation", "new_query", "stage", stage).timer().count()).isEqualTo(1));
    }

    @Test
    void 후보수_상한거절은_별도_내부사유로_집계한다() {
        var mapService = mock(RestaurantMapService.class);
        var store = mock(RedisMapSessionStore.class);
        var properties = enabledProperties();
        var registry = new SimpleMeterRegistry();
        when(store.admit(any(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(Instant.now());
        when(mapService.findCandidates(any(), anyInt()))
                .thenThrow(new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED));
        var service = new RestaurantMapPageService(mapService, mock(RestaurantMapPageReader.class), store,
                new MapCursorCodec(properties), new MapSessionLimits(), new MapCapacityMetrics(registry));
        var request = new RestaurantMapPageRequest(MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null),
                RestaurantMapSort.RECOMMEND, null, null);

        assertThatThrownBy(() -> service.getPage(request, "caller"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED));
        assertThat(registry.get("hashi.restaurant.map.capacity.rejected")
                .tag("reason", "candidate_count").counter().count()).isEqualTo(1);
        assertThat(registry.get("hashi.restaurant.map.stage.duration")
                .tags("operation", "new_query", "stage", "admit").timer().count()).isEqualTo(1);
        assertThat(registry.get("hashi.restaurant.map.stage.duration")
                .tags("operation", "new_query", "stage", "candidates").timer().count()).isEqualTo(1);
        assertThat(registry.find("hashi.restaurant.map.stage.duration")
                .tags("operation", "new_query", "stage", "save").timer()).isNull();
    }

    @Test
    void 동시상한은_대기없이_거절하고_성공과_실패_모두_실행자리를_반환한다() throws Exception {
        var reader = mock(RestaurantMapPageReader.class);
        var store = mock(RedisMapSessionStore.class);
        var properties = enabledProperties();
        var limits = new MapSessionLimits();
        limits.setConcurrentRequests(1);
        var registry = new SimpleMeterRegistry();
        var service = new RestaurantMapPageService(mock(RestaurantMapService.class), reader, store,
                new MapCursorCodec(properties), limits, new MapCapacityMetrics(registry));
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
                assertThat(registry.get("hashi.restaurant.map.capacity.rejected")
                        .tag("reason", "concurrent_requests").counter().count()).isEqualTo(1);
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

    private MapSessionProperties enabledProperties() {
        var properties = new MapSessionProperties();
        properties.setEnabled(true);
        properties.setSigningKey(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
