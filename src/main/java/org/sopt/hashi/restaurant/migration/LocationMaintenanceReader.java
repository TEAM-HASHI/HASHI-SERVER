package org.sopt.hashi.restaurant.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Scalar, bounded keyset reads. Addresses and coordinates never enter inspection DTOs. */
@Repository
public class LocationMaintenanceReader {
    static final String PAGE_SQL = """
            SELECT r.id, r.deleted, l.address_revision, l.request_id, l.status, l.source,
                l.obtained_at, l.valid_until
            FROM restaurant r LEFT JOIN restaurant_location l ON l.id = r.location_id
            WHERE r.id > ? AND r.id <= ? ORDER BY r.id LIMIT ?
            """;
    static final String PURGE_SQL = """
            SELECT r.id, r.deleted, l.address_revision, l.request_id, l.status, l.source,
                l.obtained_at, l.valid_until
            FROM restaurant_location l JOIN restaurant r ON r.location_id = l.id
            WHERE l.status = 'READY' AND l.source = 'GOOGLE_GEOCODING' AND l.valid_until <= ?
            ORDER BY l.valid_until, l.id LIMIT ?
            """;
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final JdbcTemplate jdbc;

    public LocationMaintenanceReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDateTime now() {
        return LocalDateTime.parse(jdbc.queryForObject(
                "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%f')", String.class));
    }

    long upperId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM restaurant", Long.class);
    }

    List<Candidate> page(long after, long upper, int limit) {
        return jdbc.query(PAGE_SQL, LocationMaintenanceReader::candidate, after, upper, limit);
    }

    Long nextId(long after, long upper) {
        return jdbc.query("SELECT id FROM restaurant WHERE id > ? AND id <= ? ORDER BY id LIMIT 1",
                (rs, row) -> rs.getLong(1), after, upper).stream().findFirst().orElse(null);
    }

    List<Candidate> purgeCandidates(LocalDateTime cutoff, int limit) {
        // Bind UTC calendar text. setTimestamp would depend on the JDBC/session timezone.
        return jdbc.query(PURGE_SQL, LocationMaintenanceReader::candidate, sqlTime(cutoff), limit);
    }

    long purgeRemaining(LocalDateTime cutoff) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant_location l JOIN restaurant r ON r.location_id=l.id
                WHERE l.status='READY' AND l.source='GOOGLE_GEOCODING' AND l.valid_until <= ?
                """, Long.class, sqlTime(cutoff));
    }

    static String sqlTime(LocalDateTime time) {
        return time.format(SQL_TIME);
    }

    private static Candidate candidate(ResultSet rs, int row) throws SQLException {
        String request = rs.getString("request_id");
        return new Candidate(rs.getLong("id"), rs.getBoolean("deleted"), rs.getLong("address_revision"),
                request == null ? null : UUID.fromString(request), rs.getString("status"), rs.getString("source"),
                rs.getObject("obtained_at", LocalDateTime.class), rs.getObject("valid_until", LocalDateTime.class));
    }

    public record Candidate(long restaurantId, boolean deleted, long revision, UUID requestId,
                            String status, String source, LocalDateTime obtainedAt, LocalDateTime validUntil) {
        String category(LocalDateTime asOf, LocalDateTime refreshBefore) {
            if (deleted) {
                return "DELETED";
            }
            if (status == null) {
                return "UNRESOLVED";
            }
            if (!"READY".equals(status)) {
                return status;
            }
            if (!validUntil.isAfter(asOf)) {
                return "EXPIRED";
            }
            return "GOOGLE_GEOCODING".equals(source) && !validUntil.isAfter(refreshBefore)
                    ? "REFRESH_DUE" : "VALID";
        }
    }
}
