package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics.Operation;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics.Stage;

class MapCapacityMetricsTest {

    @Test
    void 단계_타이머는_실패도_기록하고_operation과_stage만_고정_태그로_사용한다() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MapCapacityMetrics(registry);

        for (Operation operation : Operation.values()) {
            for (Stage stage : Stage.values()) {
                assertThat(metrics.record(operation, stage, () -> "recorded")).isEqualTo("recorded");
            }
        }
        assertThatThrownBy(() -> metrics.record(Operation.NEW_QUERY, Stage.CANDIDATES, () -> {
            throw new IllegalStateException("synthetic failure");
        })).isInstanceOf(IllegalStateException.class);

        var timers = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(MapCapacityMetrics.STAGE_DURATION_METRIC))
                .toList();
        assertThat(timers).hasSize(Operation.values().length * Stage.values().length)
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                            .containsExactlyInAnyOrder("operation", "stage");
                    assertThat(meter.getId().getTag("operation"))
                            .isIn("new_query", "sort_change", "next_page");
                    assertThat(meter.getId().getTag("stage"))
                            .isIn("admit", "load_session", "candidates", "save", "read_page", "touch");
                });
        assertThat(registry.get(MapCapacityMetrics.STAGE_DURATION_METRIC)
                .tags("operation", "new_query", "stage", "candidates")
                .timer().count()).isEqualTo(2);
    }
}
