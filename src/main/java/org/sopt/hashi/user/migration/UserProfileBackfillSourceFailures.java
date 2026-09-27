package org.sopt.hashi.user.migration;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.media.MediaBackfillTarget;

/** 한 실행에서 관측한 source 실패만 집계하며 원시 후보 정보는 받지 않는다. */
@Slf4j
class UserProfileBackfillSourceFailures {

    static final String METRIC_NAME = "hashi.user.profile.backfill.source.failures";

    private final UserProfileBackfillMode mode;
    private final MeterRegistry meterRegistry;
    private final Map<Reason, Long> counts = new EnumMap<>(Reason.class);

    UserProfileBackfillSourceFailures(
            UserProfileBackfillMode mode,
            MeterRegistry meterRegistry
    ) {
        this.mode = Objects.requireNonNull(mode, "mode is required");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    void record(Reason reason) {
        counts.merge(Objects.requireNonNull(reason, "reason is required"), 1L, Long::sum);
        try {
            meterRegistry.counter(METRIC_NAME,
                    "target", MediaBackfillTarget.USER_PROFILE.name(),
                    "mode", mode.name(), "reason", reason.name()).increment();
        } catch (RuntimeException exception) {
            // 관측 장애가 커밋된 cursor나 나머지 후보 처리를 바꾸지 않으며 예외 payload는 남기지 않는다.
            log.warn("User profile backfill failure metric unavailable: mode={}, reason={}, errorType={}",
                    mode, reason, exception.getClass().getSimpleName());
        }
    }

    Map<Reason, Long> snapshot() {
        return Map.copyOf(counts);
    }
}
