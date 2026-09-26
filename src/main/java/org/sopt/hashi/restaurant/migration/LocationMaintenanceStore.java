package org.sopt.hashi.restaurant.migration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LocationMaintenanceStore {
    private final JdbcTemplate jdbc;

    public LocationMaintenanceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void create(LocationMaintenanceProperties options, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO restaurant_location_maintenance_run
                    (id, mode, state, after_id, upper_id, cursor_id, as_of, refresh_before,
                     max_registrations, max_calls, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id=id
                """, options.requiredRunId().toString(), options.mode().name(), options.afterId(),
                options.requiredUpperId(), options.afterId(), LocationMaintenanceReader.sqlTime(now),
                LocationMaintenanceReader.sqlTime(now.plus(options.refreshAhead())),
                options.maxRegistrations(), options.maxCalls(), LocationMaintenanceReader.sqlTime(now));
    }

    Run read(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM restaurant_location_maintenance_run WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Run(id,
                        LocationMaintenanceProperties.Mode.valueOf(rs.getString("mode")), rs.getString("state"),
                        rs.getLong("after_id"), rs.getLong("upper_id"), rs.getLong("cursor_id"),
                        rs.getObject("as_of", LocalDateTime.class), rs.getObject("refresh_before", LocalDateTime.class),
                        rs.getInt("max_registrations"), rs.getInt("max_calls"), rs.getLong("scanned"), rs.getInt("enqueued")),
                id.toString()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown run-id"));
    }

    void state(UUID id, String state) {
        jdbc.update("UPDATE restaurant_location_maintenance_run SET state=?, updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                state, id.toString());
    }

    void progress(UUID id, long cursor, Long jobId) {
        if (jobId != null) {
            jdbc.update("INSERT INTO restaurant_location_maintenance_job (run_id, job_id) VALUES (?, ?)",
                    id.toString(), jobId);
        }
        jdbc.update("""
                UPDATE restaurant_location_maintenance_run SET cursor_id=?, scanned=scanned+1,
                    enqueued=enqueued+?, updated_at=UTC_TIMESTAMP(6) WHERE id=?
                """, cursor, jobId == null ? 0 : 1, id.toString());
    }

    Completion completion(UUID id, LocalDateTime now) {
        Map<String, Long> states = new TreeMap<>();
        jdbc.query("""
                SELECT COALESCE(j.state, 'MISSING_JOB') AS state, COUNT(*) AS amount
                FROM restaurant_location_maintenance_job m LEFT JOIN restaurant_location_job j ON j.id=m.job_id
                WHERE m.run_id=? GROUP BY j.state
                """, rs -> { states.put(rs.getString("state"), rs.getLong("amount")); }, id.toString());
        long attempts = jdbc.queryForObject("""
                SELECT COALESCE(SUM(j.attempt), 0) FROM restaurant_location_maintenance_job m
                JOIN restaurant_location_job j ON j.id=m.job_id WHERE m.run_id=?
                """, Long.class, id.toString());
        long usable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant_location_maintenance_job m
                JOIN restaurant_location_job j ON j.id=m.job_id
                JOIN restaurant r ON r.id=j.restaurant_id JOIN restaurant_location l ON l.id=r.location_id
                WHERE m.run_id=? AND r.deleted=false AND j.state='SUCCEEDED' AND l.status='READY'
                    AND l.address_revision=j.address_revision AND l.request_id=j.request_id AND l.valid_until > ?
                """, Long.class, id.toString(), LocationMaintenanceReader.sqlTime(now));
        return new Completion(now.toInstant(ZoneOffset.UTC), Map.copyOf(states), attempts, usable);
    }

    public record Run(UUID id, LocationMaintenanceProperties.Mode mode, String state,
                      long afterId, long upperId, long cursorId, LocalDateTime asOf, LocalDateTime refreshBefore,
                      int maxRegistrations, int maxCalls, long scanned, int enqueued) {
        boolean hasCapacity() {
            return enqueued < maxRegistrations
                    && enqueued < maxCalls / LocationMaintenanceProperties.CALLS_PER_JOB;
        }
    }

    public record Completion(Instant asOfUtc, Map<String, Long> jobStates, long reservedAttempts,
                             long currentlyUsableLocations) {
    }

    public record Status(Run registration, Completion completion) {
    }
}
