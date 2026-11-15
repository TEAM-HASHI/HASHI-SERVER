package org.sopt.hashi.magazine.migration;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MagazineMediaBackfillCandidateReader {

    private static final String BANNER_UPPER_BOUND_SQL = """
            SELECT COALESCE(MAX(id), 0)
            FROM magazine
            WHERE deleted = false AND banner_key IS NOT NULL AND banner_image_asset_id IS NULL
            """;
    private static final String THUMBNAIL_UPPER_BOUND_SQL = """
            SELECT COALESCE(MAX(id), 0)
            FROM magazine
            WHERE deleted = false AND thumbnail_key IS NOT NULL AND thumbnail_image_asset_id IS NULL
            """;
    private static final String BANNER_BATCH_SQL = """
            SELECT id, banner_key AS legacy_key
            FROM magazine
            WHERE deleted = false
              AND banner_key IS NOT NULL
              AND banner_image_asset_id IS NULL
              AND id > ? AND id <= ?
            ORDER BY id ASC
            LIMIT ?
            """;
    private static final String THUMBNAIL_BATCH_SQL = """
            SELECT id, thumbnail_key AS legacy_key
            FROM magazine
            WHERE deleted = false
              AND thumbnail_key IS NOT NULL
              AND thumbnail_image_asset_id IS NULL
              AND id > ? AND id <= ?
            ORDER BY id ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    MagazineMediaBackfillCandidateReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public long findUpperBound(MagazineMediaBackfillTarget target) {
        String sql = switch (target) {
            case MAGAZINE_BANNER -> BANNER_UPPER_BOUND_SQL;
            case MAGAZINE_THUMBNAIL -> THUMBNAIL_UPPER_BOUND_SQL;
        };
        Long upperBound = jdbcTemplate.queryForObject(sql, Long.class);
        return upperBound == null ? 0L : upperBound;
    }

    @Transactional(readOnly = true)
    public List<MagazineMediaBackfillCandidate> findBatch(
            MagazineMediaBackfillTarget target,
            long cursor,
            long upperBound,
            int limit
    ) {
        if (cursor < 0 || upperBound < cursor || limit < 1) {
            throw new IllegalArgumentException("invalid magazine media backfill range");
        }
        String sql = switch (target) {
            case MAGAZINE_BANNER -> BANNER_BATCH_SQL;
            case MAGAZINE_THUMBNAIL -> THUMBNAIL_BATCH_SQL;
        };
        return jdbcTemplate.query(sql, (resultSet, rowNumber) ->
                        new MagazineMediaBackfillCandidate(
                                target, resultSet.getLong("id"), resultSet.getString("legacy_key")),
                cursor, upperBound, limit);
    }
}
