package org.sopt.hashi.user.migration;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class UserProfileBackfillCandidateReader {

    private final JdbcTemplate jdbcTemplate;

    UserProfileBackfillCandidateReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public long findUpperBound() {
        Long upperBound = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(id), 0)
                FROM users
                WHERE deleted = FALSE
                  AND profile_image_key IS NOT NULL
                  AND profile_image_asset_id IS NULL
                """, Long.class);
        return upperBound == null ? 0L : upperBound;
    }

    @Transactional(readOnly = true)
    public List<UserProfileBackfillCandidate> findBatch(long cursor, long upperBound, int limit) {
        if (cursor < 0 || upperBound < cursor || limit < 1) {
            throw new IllegalArgumentException("invalid user profile backfill range");
        }
        return jdbcTemplate.query("""
                SELECT id, profile_image_key
                FROM users
                WHERE deleted = FALSE
                  AND profile_image_key IS NOT NULL
                  AND profile_image_asset_id IS NULL
                  AND id > ? AND id <= ?
                ORDER BY id ASC
                LIMIT ?
                """, (row, rowNumber) -> new UserProfileBackfillCandidate(
                row.getLong("id"), row.getString("profile_image_key")), cursor, upperBound, limit);
    }
}
