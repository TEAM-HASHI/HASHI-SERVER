package org.sopt.hashi.review.domain;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 리뷰 모듈 소유 테이블만 읽는 사진 projection. 전체 리뷰 Entity/사진 목록을 메모리에 적재하지 않는다. */
@Repository
public class ReviewPhotoRepository {
    private static final String FROM = """
            from review_image i join review r on r.id = i.review_id
            where r.restaurant_id = :restaurantId and r.deleted = false
            """;
    private static final String ORDER = "r.created_at desc, r.id desc, i.display_order, i.id";
    private final NamedParameterJdbcTemplate jdbc;

    public ReviewPhotoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(long restaurantId, boolean distinct, Collection<String> excludedKeys) {
        String expression = distinct ? "count(distinct cast(i.file_key as binary))" : "count(*)";
        return jdbc.queryForObject("select " + expression + " " + FROM + exclusions(excludedKeys),
                parameters(restaurantId, excludedKeys), Long.class);
    }

    public List<Photo> find(long restaurantId, boolean distinct, Collection<String> excludedKeys,
                            Position after, int limit) {
        String projection = "i.id, i.file_key, r.created_at, r.id as review_id, i.display_order";
        String inner = distinct
                ? "select " + projection + ", row_number() over(partition by cast(i.file_key as binary) order by "
                        + ORDER + ") as duplicate_rank " + FROM + exclusions(excludedKeys)
                : "select " + projection + ", 1 as duplicate_rank " + FROM + exclusions(excludedKeys);
        String seek = seekAfter(after);
        MapSqlParameterSource parameters = parameters(restaurantId, excludedKeys).addValue("limit", limit);
        if (after != null) {
            parameters.addValue("createdAt", after.createdAt()).addValue("reviewId", after.reviewId())
                    .addValue("displayOrder", after.displayOrder()).addValue("imageId", after.imageId());
        }
        // 중복 순위를 먼저 계산한 뒤 seek한다. 이전 페이지의 동일 원본이 다시 노출되지 않는다.
        return jdbc.query("select * from (" + inner + ") photos where duplicate_rank = 1" + seek
                        + " order by created_at desc, review_id desc, display_order, id limit :limit", parameters,
                (rs, row) -> {
                    var createdAt = rs.getTimestamp("created_at");
                    return new Photo(rs.getLong("id"), rs.getString("file_key"),
                            new Position(createdAt == null ? null : createdAt.toLocalDateTime(),
                                    rs.getLong("review_id"), rs.getInt("display_order"), rs.getLong("id")));
                });
    }

    private static String seekAfter(Position after) {
        if (after == null) {
            return "";
        }
        if (after.createdAt() == null) {
            return """
                     and created_at is null and (review_id < :reviewId
                       or (review_id = :reviewId and display_order > :displayOrder)
                       or (review_id = :reviewId and display_order = :displayOrder and id > :imageId))
                    """;
        }
        return """
                 and (created_at is null or created_at < :createdAt
                   or (created_at = :createdAt and review_id < :reviewId)
                   or (created_at = :createdAt and review_id = :reviewId and display_order > :displayOrder)
                   or (created_at = :createdAt and review_id = :reviewId
                       and display_order = :displayOrder and id > :imageId))
                """;
    }

    public Set<String> findExistingKeys(long restaurantId, List<String> keys) {
        Set<String> result = new HashSet<>();
        for (int start = 0; start < keys.size(); start += 500) {
            List<String> batch = keys.subList(start, Math.min(start + 500, keys.size()));
            result.addAll(jdbc.query("select distinct cast(i.file_key as binary) as file_key " + FROM
                            + " and cast(i.file_key as binary) in (:keys)",
                    new MapSqlParameterSource("restaurantId", restaurantId).addValue("keys", batch),
                    (rs, row) -> rs.getString("file_key")));
        }
        return result;
    }

    private static String exclusions(Collection<String> keys) {
        return keys.isEmpty() ? "" : " and cast(i.file_key as binary) not in (:excludedKeys)";
    }

    private static MapSqlParameterSource parameters(long restaurantId, Collection<String> keys) {
        return new MapSqlParameterSource("restaurantId", restaurantId).addValue("excludedKeys", keys);
    }

    public record Position(java.time.LocalDateTime createdAt, long reviewId, int displayOrder, long imageId) { }

    public record Photo(long id, String fileKey, Position position) { }
}
