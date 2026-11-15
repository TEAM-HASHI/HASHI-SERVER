package org.sopt.hashi.media.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MediaRecoveryPropertiesTest {

    @Test
    void 운영_보존과_재시도_기본값을_적용한다() {
        MediaRecoveryProperties properties = properties(null, 0, 0, 0);

        assertThat(properties.eprResubmitAge()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.eprResubmitBatchSize()).isEqualTo(50);
        assertThat(properties.processingStaleAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.processingRetryInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.processingMaxAttempts()).isEqualTo(3);
        assertThat(properties.scanBatchSize()).isEqualTo(100);
        assertThat(properties.scanMaxBatches()).isEqualTo(10);
        assertThat(properties.pendingRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.backfillUnboundRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.failedRetention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void 음수_주기와_횟수는_거부한다() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(-1), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MediaRecoveryProperties properties(
            Duration eprAge,
            int maxAttempts,
            int batchSize,
            int maxBatches
    ) {
        return new MediaRecoveryProperties(
                true,
                eprAge,
                null,
                0,
                null,
                null,
                maxAttempts,
                batchSize,
                maxBatches,
                null,
                null,
                null,
                null,
                null
        );
    }
}
