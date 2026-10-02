package org.sopt.hashi.restaurant.migration;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationMaintenanceInspection {
    private final LocationMaintenanceReader reader;

    public LocationMaintenanceInspection(LocationMaintenanceReader reader) {
        this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report inspect(LocationMaintenanceProperties options) {
        LocalDateTime now = reader.now();
        long upper = options.upperId() == null ? Math.max(options.afterId(), reader.upperId()) : options.upperId();
        long cursor = options.afterId();
        long scanned = 0;
        long purgeDue = 0;
        long deletedGoogle = 0;
        long eligible = 0;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String category : new String[]{"UNRESOLVED", "VALID", "REFRESH_DUE", "EXPIRED", "PENDING",
                "RETRY_WAIT", "REVIEW_REQUIRED", "FAILED", "DELETED"}) {
            counts.put(category, 0L);
        }
        for (int batch = 0; batch < options.maxBatches(); batch++) {
            var candidates = reader.page(cursor, upper, options.batchSize());
            for (var candidate : candidates) {
                counts.merge(candidate.category(now, now.plus(options.refreshAhead())), 1L, Long::sum);
                boolean google = "GOOGLE_GEOCODING".equals(candidate.source());
                boolean eligibleRefresh = google && "READY".equals(candidate.status())
                        && !candidate.obtainedAt().isAfter(now)
                        && !candidate.validUntil().isAfter(now.plus(options.refreshAhead()))
                        && options.refreshAhead().compareTo(Duration.between(candidate.obtainedAt(), candidate.validUntil())) < 0;
                boolean eligibleState = options.mode() == LocationMaintenanceProperties.Mode.BACKFILL
                        ? candidate.status() == null : eligibleRefresh;
                if (!candidate.deleted() && eligibleState) {
                    eligible++;
                }
                if (google && !candidate.validUntil().isAfter(now.plus(options.purgeAhead()))) {
                    purgeDue++;
                }
                if (google && candidate.deleted()) {
                    deletedGoogle++;
                }
                cursor = candidate.restaurantId();
                scanned++;
            }
            if (candidates.size() < options.batchSize()) {
                break;
            }
        }
        boolean partial = reader.nextId(cursor, upper) != null;
        long registrationEstimate = Math.min(eligible, Math.min(options.maxRegistrations(),
                options.maxCalls() / LocationMaintenanceProperties.CALLS_PER_JOB));
        return new Report(now.toInstant(ZoneOffset.UTC), options.afterId(), upper, cursor, scanned,
                options.batchSize() * options.maxBatches(), partial, Map.copyOf(counts), purgeDue, deletedGoogle,
                options.mode(), eligible, registrationEstimate, registrationEstimate * LocationMaintenanceProperties.CALLS_PER_JOB);
    }

    public record Report(Instant asOfUtc, long afterId, long upperId, long lastInspectedId, long inspected,
                         int inspectionLimit, boolean partial, Map<String, Long> categories,
                         long googlePurgeDueInInspectedRows, long deletedGoogleInInspectedRows,
                         LocationMaintenanceProperties.Mode mode, long eligibleByLocationStateInInspectedRows,
                         long registrationEstimateInInspectedRows, long reservedCallCeilingForEstimate) {
    }
}
