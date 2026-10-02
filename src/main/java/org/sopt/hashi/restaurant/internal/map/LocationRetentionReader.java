package org.sopt.hashi.restaurant.internal.map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Permanent retention reads remain available after the temporary maintenance runner is removed. */
@Repository
public class LocationRetentionReader {
    static final String PURGE_SQL = """
            SELECT r.id, r.deleted, l.address_revision, l.request_id, l.status, l.source,
                l.obtained_at, l.valid_until
            FROM restaurant_location l JOIN restaurant r ON r.location_id = l.id
            WHERE l.status = 'READY' AND l.source = 'GOOGLE_GEOCODING' AND l.valid_until <= ?
            ORDER BY l.valid_until, l.id LIMIT ?
            """;
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final JdbcTemplate jdbc;

    public LocationRetentionReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDateTime now() {
        return LocalDateTime.parse(jdbc.queryForObject(
                "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%f')", String.class));
    }

    public List<Candidate> purgeCandidates(LocalDateTime cutoff, int limit) {
        // Bind UTC calendar text. setTimestamp would depend on the JDBC/session timezone.
        return jdbc.query(PURGE_SQL, LocationRetentionReader::candidate, sqlTime(cutoff), limit);
    }

    public long purgeRemaining(LocalDateTime cutoff) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant_location l JOIN restaurant r ON r.location_id=l.id
                WHERE l.status='READY' AND l.source='GOOGLE_GEOCODING' AND l.valid_until <= ?
                """, Long.class, sqlTime(cutoff));
    }

    private static String sqlTime(LocalDateTime time) {
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
    }
}
