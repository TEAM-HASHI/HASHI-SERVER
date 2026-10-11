package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;

class MapSessionLimitsTest {
    @Test
    void 기본값은_5분유휴와_30분상한이며_후보상한은_byte예산에서_계산한다() {
        var limits = new MapSessionLimits();
        limits.validate();
        assertThat(limits.getIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(limits.getMaxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(limits.candidateCapacity()).isEqualTo(32_768);
        limits.setSnapshotBytes(65_536);
        assertThat(limits.candidateCapacity()).isEqualTo(2048);
    }

    @Test
    void 잘못된_시간_메모리_호출수_설정은_요청에서_failclosed한다() {
        List<Consumer<MapSessionLimits>> invalid = List.of(
                value -> value.setConcurrentRequests(0),
                value -> value.setConcurrentRequests(17),
                value -> value.setIdleTimeout(Duration.ZERO),
                value -> value.setMaxLifetime(Duration.ofMinutes(31)),
                value -> value.setMaxLifetime(Duration.ofMinutes(1)),
                value -> value.setSnapshotBytes(1),
                value -> value.setTotalBytes(1024),
                value -> value.setRedisHeadroom(1024),
                value -> value.setRedisMemoryCeiling(1024),
                value -> value.setSessions(4097),
                value -> value.setCallersPerMinute(4097),
                value -> value.setNewQueriesPerCaller(121));
        for (var configure : invalid) {
            var limits = new MapSessionLimits();
            configure.accept(limits);
            assertThatThrownBy(limits::validate).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE));
        }
    }
}
