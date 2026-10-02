package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import java.util.UUID;
import org.sopt.hashi.restaurant.internal.map.LocationRetentionProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.map.maintenance")
public record LocationMaintenanceProperties(
        Command command, Mode mode, boolean execute, UUID runId, long afterId, Long upperId,
        Integer batchSize, Integer maxBatches, Integer maxRegistrations, Integer maxCalls,
        Duration refreshAhead, Duration purgeAhead, boolean retentionEnabled, Duration pollDelay
) {
    /** #224 validates max-attempts in [1,8], including after application restarts. */
    public static final int CALLS_PER_JOB = 8;

    public LocationMaintenanceProperties {
        command = command == null ? Command.DRY_RUN : command;
        mode = mode == null ? Mode.BACKFILL : mode;
        var retention = new LocationRetentionProperties(batchSize, maxBatches, refreshAhead, purgeAhead,
                retentionEnabled, pollDelay);
        batchSize = retention.batchSize();
        maxBatches = retention.maxBatches();
        maxRegistrations = maxRegistrations == null ? 10 : maxRegistrations;
        maxCalls = maxCalls == null ? 80 : maxCalls;
        refreshAhead = retention.refreshAhead();
        purgeAhead = retention.purgeAhead();
        pollDelay = retention.pollDelay();
        range(maxRegistrations, 1, 10000, "max-registrations");
        range(maxCalls, 0, 80000, "max-calls");
        if (afterId < 0 || (upperId != null && upperId < afterId)) {
            throw new IllegalArgumentException("Invalid maintenance ID range");
        }
    }

    public LocationRetentionProperties retentionOptions() {
        return new LocationRetentionProperties(batchSize, maxBatches, refreshAhead, purgeAhead,
                retentionEnabled, pollDelay);
    }

    void requireWriteOptIn() {
        if (!execute) {
            throw new IllegalArgumentException("Mutation requires hashi.map.maintenance.execute=true");
        }
    }

    UUID requiredRunId() {
        if (runId == null) {
            throw new IllegalArgumentException("run-id is required");
        }
        return runId;
    }

    long requiredUpperId() {
        if (upperId == null) {
            throw new IllegalArgumentException("START requires the upper-id reviewed in DRY_RUN");
        }
        return upperId;
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }

    public enum Command { DRY_RUN, START, RESUME, STATUS, STOP, PURGE }

    public enum Mode { BACKFILL, REFRESH }
}
