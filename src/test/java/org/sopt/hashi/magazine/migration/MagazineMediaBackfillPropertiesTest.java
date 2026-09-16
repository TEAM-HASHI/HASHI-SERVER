package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MagazineMediaBackfillPropertiesTest {

    @Test
    void PREPARE와_ATTACH는_canonical_run_ID가_필수다() {
        UUID runId = UUID.fromString("048ce2b2-0d3e-477c-8ebd-3a43aabb62a9");

        MagazineMediaBackfillProperties properties = properties(
                true, runId.toString(), MagazineMediaBackfillMode.PREPARE,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofMillis(200));

        assertThat(properties.requiredRunId()).isEqualTo(runId);
        assertThatThrownBy(() -> properties(
                true, "", MagazineMediaBackfillMode.ATTACH,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                true, runId.toString().toUpperCase(), MagazineMediaBackfillMode.ATTACH,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void DRY_RUN과_비활성_설정은_run_ID없이_구성할_수_있다() {
        assertThat(properties(
                true, "", MagazineMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofMinutes(5), 3, Duration.ZERO).enabled()).isTrue();
        assertThat(properties(
                false, "", MagazineMediaBackfillMode.ATTACH,
                50, 10, Duration.ofMinutes(5), 3, Duration.ZERO).enabled()).isFalse();
    }

    @Test
    void batch_lease_retry_범위를_벗어난_설정은_거부한다() {
        assertThatThrownBy(() -> properties(
                false, "", MagazineMediaBackfillMode.DRY_RUN,
                0, 10, Duration.ofMinutes(5), 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                false, "", MagazineMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofSeconds(29), 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                false, "", MagazineMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofMinutes(5), 6, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                false, "", MagazineMediaBackfillMode.DRY_RUN,
                50, 10, Duration.ofMinutes(5), 3, Duration.ofSeconds(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MagazineMediaBackfillProperties properties(
            boolean enabled,
            String runId,
            MagazineMediaBackfillMode mode,
            int batchSize,
            int maxBatches,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryDelay
    ) {
        return new MagazineMediaBackfillProperties(
                enabled, runId, MagazineMediaBackfillTarget.MAGAZINE_BANNER,
                mode, batchSize, maxBatches, leaseDuration, maxAttempts, retryDelay);
    }
}
