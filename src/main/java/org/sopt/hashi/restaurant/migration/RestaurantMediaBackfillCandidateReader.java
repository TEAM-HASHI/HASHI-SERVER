package org.sopt.hashi.restaurant.migration;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RestaurantMediaBackfillCandidateReader {

    private static final String IMAGE_UPPER_BOUND_SQL = """
            SELECT COALESCE(MAX(id), 0)
            FROM restaurant_image
            WHERE file_key IS NOT NULL AND image_asset_id IS NULL
            """;
    private static final String MENU_UPPER_BOUND_SQL = """
            SELECT COALESCE(MAX(id), 0)
            FROM restaurant_menu
            WHERE image_key IS NOT NULL AND image_asset_id IS NULL
            """;
    private static final String IMAGE_BATCH_SQL = """
            SELECT id, restaurant_id, file_key
            FROM restaurant_image
            WHERE file_key IS NOT NULL
              AND image_asset_id IS NULL
              AND id > ?
              AND id <= ?
            ORDER BY id ASC
            LIMIT ?
            """;
    private static final String MENU_BATCH_SQL = """
            SELECT id, restaurant_id, image_key
            FROM restaurant_menu
            WHERE image_key IS NOT NULL
              AND image_asset_id IS NULL
              AND id > ?
              AND id <= ?
            ORDER BY id ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    RestaurantMediaBackfillCandidateReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public long findUpperBound(RestaurantMediaBackfillTarget target) {
        Long upperBound = jdbcTemplate.queryForObject(upperBoundSql(target), Long.class);
        return upperBound == null ? 0L : upperBound;
    }

    @Transactional(readOnly = true)
    public List<RestaurantMediaBackfillCandidate> findBatch(
            RestaurantMediaBackfillTarget target,
            long cursor,
            long upperBound,
            int limit
    ) {
        if (cursor < 0 || upperBound < cursor || limit < 1) {
            throw new IllegalArgumentException("invalid restaurant media backfill range");
        }
        String keyColumn = target == RestaurantMediaBackfillTarget.RESTAURANT_IMAGE
                ? "file_key" : "image_key";
        return jdbcTemplate.query(batchSql(target), (resultSet, rowNumber) ->
                        new RestaurantMediaBackfillCandidate(
                                target,
                                resultSet.getLong("id"),
                                resultSet.getLong("restaurant_id"),
                                resultSet.getString(keyColumn)
                        ),
                cursor, upperBound, limit);
    }

    private String upperBoundSql(RestaurantMediaBackfillTarget target) {
        return switch (target) {
            case RESTAURANT_IMAGE -> IMAGE_UPPER_BOUND_SQL;
            case RESTAURANT_MENU -> MENU_UPPER_BOUND_SQL;
        };
    }

    private String batchSql(RestaurantMediaBackfillTarget target) {
        return switch (target) {
            case RESTAURANT_IMAGE -> IMAGE_BATCH_SQL;
            case RESTAURANT_MENU -> MENU_BATCH_SQL;
        };
    }
}
