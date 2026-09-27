package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RestaurantMediaBackfillPropertiesTest {

    @Test
    void PREPARE와_ATTACH는_canonical_run_ID가_필수다() {
        UUID runId = UUID.randomUUID();

        RestaurantMediaBackfillProperties properties = properties(
                true, runId.toString(), RestaurantMediaBackfillMode.PREPARE,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofMillis(200));

        assertThat(properties.requiredRunId()).isEqualTo(runId);
        assertThatThrownBy(() -> properties(
                true, "", RestaurantMediaBackfillMode.ATTACH,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                true, runId.toString().toUpperCase(), RestaurantMediaBackfillMode.ATTACH,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void DRY_RUN과_비활성_설정은_run_ID없이_구성할_수_있다() {
        assertThat(properties(
                true, "", RestaurantMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofMinutes(5), 3, Duration.ZERO).enabled()).isTrue();
        assertThat(properties(
                false, "", RestaurantMediaBackfillMode.ATTACH,
                50, 10, Duration.ofMinutes(5), 3, Duration.ZERO).enabled()).isFalse();
    }

    @Test
    void batch_lease_retry_범위를_벗어난_설정은_거부한다() {
        assertThatThrownBy(() -> properties(
                false, "", RestaurantMediaBackfillMode.DRY_RUN,
                0, 10, Duration.ofMinutes(5), 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                false, "", RestaurantMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofSeconds(29), 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                false, "", RestaurantMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofMinutes(5), 6, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                false, "", RestaurantMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofSeconds(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RestaurantMediaBackfillProperties properties(
            boolean enabled,
            String runId,
            RestaurantMediaBackfillMode mode,
            int batchSize,
            int maxBatches,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryDelay
    ) {
        return new RestaurantMediaBackfillProperties(
                enabled, runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                mode, batchSize, maxBatches, leaseDuration, maxAttempts, retryDelay);
    }
}
