package org.sopt.hashi.restaurant.internal.map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.sopt.hashi.restaurant.internal.map.places.GooglePlacesProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One bounded set of aggregate series. Never attach restaurant IDs, addresses or exception text. */
@Component
@ConditionalOnProperty(prefix = "hashi.map.maintenance", name = "retention-enabled", havingValue = "true")
public class LocationMaintenanceMetrics {
    private final JdbcTemplate jdbc;
    private final LocationJobProperties jobOptions;
    private final GooglePlacesProperties placesOptions;
    private final LocationRetentionProperties retentionOptions;
    private final Map<String, AtomicLong> counts = new LinkedHashMap<>();
    private final AtomicLong observedAt = new AtomicLong();

    public LocationMaintenanceMetrics(JdbcTemplate jdbc, MeterRegistry registry, LocationJobProperties jobOptions,
                                      GooglePlacesProperties placesOptions,
                                      LocationRetentionProperties retentionOptions) {
        this.jdbc = jdbc;
        this.jobOptions = jobOptions;
        this.placesOptions = placesOptions;
        this.retentionOptions = retentionOptions;
        for (String reason : new String[]{"access_denied", "configuration_error", "retrying",
                "attempts_exhausted", "expiry_soon", "stalled", "refresh_window_invalid"}) {
            var value = new AtomicLong();
            counts.put(reason, value);
            Gauge.builder("hashi.map.maintenance.issues", value, AtomicLong::doubleValue)
                    .tag("reason", reason).register(registry);
        }
        Gauge.builder("hashi.map.maintenance.observed.timestamp", observedAt, AtomicLong::doubleValue)
                .register(registry);
    }

    public void refresh() {
        var jobs = jdbc.queryForMap("""
                SELECT
                    COALESCE(SUM(j.failure_code='ACCESS_DENIED'),0) access_denied,
                    COALESCE(SUM(j.failure_code='CONFIGURATION_ERROR'),0) configuration_error,
                    COALESCE(SUM((j.attempt >= 3 AND j.state <> 'LEASED' OR j.attempt >= 4) AND j.failure_code IS NOT NULL
                        AND j.state IN ('PENDING','LEASED','RETRY_WAIT')),0) retrying,
                    COALESCE(SUM(j.failure_code='ATTEMPTS_EXHAUSTED'),0) attempts_exhausted,
                    COALESCE(SUM(? AND ((j.operation='GEOCODING' AND EXISTS (
                        SELECT 1 FROM restaurant_geocoding_budget b
                        WHERE b.id=1 AND b.enabled=true AND b.daily_limit>0 AND b.max_concurrent>0
                            AND (b.blocked_until IS NULL OR b.blocked_until <= UTC_TIMESTAMP(6))
                            AND (b.budget_day IS NULL OR b.budget_day < UTC_DATE()
                                OR (b.budget_day = UTC_DATE() AND b.reserved_calls < b.daily_limit))))
                        OR (j.operation='PLACE_DETAILS' AND ? AND EXISTS (
                            SELECT 1 FROM restaurant_places_budget b
                            WHERE b.operation='DETAILS' AND b.enabled=true
                                AND b.daily_limit>0 AND b.minute_limit>0
                                AND (b.blocked_until IS NULL OR b.blocked_until <= UTC_TIMESTAMP(6))
                                AND (b.budget_day IS NULL OR b.budget_day < UTC_DATE()
                                    OR (b.budget_day = UTC_DATE() AND b.daily_used < b.daily_limit))
                                AND (b.minute_window_start IS NULL
                                    OR b.minute_window_start < DATE_FORMAT(
                                        UTC_TIMESTAMP(6), '%Y-%m-%d %H:%i:00')
                                    OR b.minute_used < b.minute_limit))))
                        AND ((j.state IN ('PENDING','RETRY_WAIT')
                            AND j.next_attempt_at <= UTC_TIMESTAMP(6)-INTERVAL 15 MINUTE
                            AND j.updated_at <= UTC_TIMESTAMP(6)-INTERVAL 15 MINUTE)
                        OR (j.state='LEASED' AND j.lease_until <= UTC_TIMESTAMP(6)-INTERVAL 15 MINUTE))),0) stalled
                FROM restaurant_location_job j JOIN restaurant r ON r.id=j.restaurant_id
                JOIN restaurant_location l ON l.id=r.location_id
                WHERE r.deleted=false AND l.address_revision=j.address_revision AND l.request_id=j.request_id
                """, jobOptions.enabled() && jobOptions.isConfigured(), placesOptions.enabled());
        long expiry = jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant_location
                WHERE source IN ('GOOGLE_GEOCODING', 'GOOGLE_PLACES')
                    AND valid_until <= UTC_TIMESTAMP(6)+INTERVAL 24 HOUR
                """, Long.class);
        long now = jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class);
        jobs.forEach((reason, value) -> counts.get(reason).set(((Number) value).longValue()));
        counts.get("expiry_soon").set(expiry);
        counts.get("refresh_window_invalid").set(jobOptions.enabled() && jobOptions.retention() != null
                && retentionOptions.refreshAhead().compareTo(jobOptions.retention()) >= 0 ? 1 : 0);
        observedAt.set(now);
    }
}
