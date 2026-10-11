package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

class LocationRetryPolicyTest {
    private final LocationRetryPolicy policy = new LocationRetryPolicy();

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = {"TIMEOUT", "CONNECTION_ERROR", "TRANSIENT_ERROR",
            "QUOTA_EXCEEDED", "CAPACITY_EXCEEDED", "CANCELLED"})
    void 일시_오류는_합의한_간격과_최대_10퍼센트_지연을_사용한다(FailureKind kind) {
        var seconds = List.of(60L, 300L, 1800L, 7200L, 21600L, 43200L, 86400L, 86400L);
        for (int attempt = 1; attempt <= 8; attempt++) {
            long base = seconds.get(attempt - 1);
            assertThat(policy.delay(kind, attempt)).isBetween(Duration.ofSeconds(base),
                    Duration.ofSeconds(base + base / 10));
        }
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = {"DISABLED", "INVALID_REQUEST", "ACCESS_DENIED",
            "CONFIGURATION_ERROR", "INVALID_RESPONSE", "RESPONSE_TOO_LARGE", "REDIRECT_REJECTED"})
    void 영구_오류는_자동_재시도하지_않는다(FailureKind kind) {
        assertThat(policy.canRetry(kind)).isFalse();
        assertThatThrownBy(() -> policy.delay(kind, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘못된_시도_횟수는_거절한다() {
        assertThatThrownBy(() -> policy.delay(FailureKind.TIMEOUT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delay(FailureKind.TIMEOUT, 9)).isInstanceOf(IllegalArgumentException.class);
    }
}
