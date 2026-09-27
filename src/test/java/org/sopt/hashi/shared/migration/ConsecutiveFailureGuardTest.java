package org.sopt.hashi.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConsecutiveFailureGuardTest {
    @Test
    void 연속_다섯번째부터_중단을_요청한다() {
        ConsecutiveFailureGuard guard = new ConsecutiveFailureGuard(5);
        for (int i = 0; i < 4; i++) {
            assertThat(guard.record(true)).isFalse();
        }
        assertThat(guard.record(true)).isTrue();
        assertThat(guard.record(true)).isTrue();
    }

    @Test
    void 다른_결과와_새_실행은_연속_계수를_초기화한다() {
        ConsecutiveFailureGuard guard = new ConsecutiveFailureGuard(5);
        for (int i = 0; i < 4; i++) {
            guard.record(true);
        }
        assertThat(guard.record(false)).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(guard.record(true)).isFalse();
        }
        assertThat(new ConsecutiveFailureGuard(5).record(true)).isFalse();
    }

    @Test
    void 양수가_아닌_한도를_거부한다() {
        assertThatThrownBy(() -> new ConsecutiveFailureGuard(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
