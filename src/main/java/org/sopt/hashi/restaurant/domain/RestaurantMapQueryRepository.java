package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent.ResultBounds;
import org.sopt.hashi.restaurant.RestaurantMapInfo.AttributionInfo;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataRetrievalFailureException;

/** 지도 전용 값 projection. 모든 SQL은 restaurant 모듈 안의 테이블만 사용한다. */
@Repository
public class RestaurantMapQueryRepository {

    private static final ObjectMapper ATTRIBUTIONS_MAPPER = new ObjectMapper();

    private static final DateTimeFormatter UTC_DATETIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");
    private static final String USABLE_LOCATION = """
            l.latitude is not null and l.longitude is not null
            and l.latitude between -90 and 90 and l.longitude between -180 and 180
            and l.source is not null and l.obtained_at is not null
            and l.obtained_at < l.valid_until
            and l.valid_until > cast(:now as datetime(6))
            """;
    private final EntityManager entityManager;

    public RestaurantMapQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** maxResults는 후보 상한 + 1이다. 호출자가 초과를 감지하고 전체 실패시킨다. */
    public List<RestaurantMapCandidate> findCandidates(MapSearchCriteria criteria, Instant now, int maxResults) {
        return candidateRows(criteria, now, null, maxResults);
    }

    public List<RestaurantMapCandidate> findMatchingCandidates(MapSearchCriteria criteria, Instant now, List<Long> ids) {
        return ids.isEmpty() ? List.of() : candidateRows(criteria, now, ids, null);
    }

    /** 페이지 크기와 무관하게 검색 조건에 맞는 모든 유효 좌표의 개수와 경계를 집계한다. */
    public MapSearchResultExtent findResultExtent(MapSearchCriteria criteria, Instant now) {
        StringBuilder sql = matchingRowsSql("""
                select count(*), min(l.latitude), max(l.latitude), min(l.longitude), max(l.longitude),
                       date_format(min(l.valid_until), '%Y-%m-%dT%H:%i:%s.%f')
                """, criteria);
        Object[] row = (Object[]) bindCriteria(entityManager.createNativeQuery(sql.toString()), criteria, now)
                .getSingleResult();
        long totalCount = number(row[0]);
        if (totalCount == 0) {
            return MapSearchResultExtent.empty();
        }
        return new MapSearchResultExtent(totalCount, new ResultBounds(
                (BigDecimal) row[1], (BigDecimal) row[2], (BigDecimal) row[3], (BigDecimal) row[4]), instant(row[5]));
    }

    public Optional<MapQueryBounds> findActiveRegionBounds(Long id) {
        List<Object[]> rows = rows(entityManager.createNativeQuery("""
                select south, north, west, east from map_region where id = :id and active = true
                """).setParameter("id", id));
        return rows.stream().findFirst().map(row -> bounds(row, 0));
    }

    /** 위치가 무효한 공개 식당도 남긴다. 무효 좌표는 SQL LEFT JOIN에서 이미 제외한다. */
    public List<RestaurantMapInfo> findActiveMapInfos(List<Long> ids, Instant now) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Query query = entityManager.createNativeQuery("""
                select r.id, r.name, r.place_type, r.genre, l.latitude, l.longitude,
                       date_format(l.valid_until, '%Y-%m-%dT%H:%i:%s.%f'),
                       cast(l.places_attributions as char)
                from restaurant r left join restaurant_location l on l.id = r.location_id and
                """ + USABLE_LOCATION + " where r.deleted = false and r.id in (:ids)")
                .setParameter("ids", ids).setParameter("now", utc(now));
        return rows(query).stream().map(row -> new RestaurantMapInfo(number(row[0]), (String) row[1],
                RestaurantPlaceType.valueOf((String) row[2]).value(),
                RestaurantGenre.valueOf((String) row[3]).value(),
                row[4] == null ? null : new LocationInfo((BigDecimal) row[4], (BigDecimal) row[5], instant(row[6]),
                        attributions(row[7]))))
                .toList();
    }

    /** 소속은 수동 지정 ID로 판단하며 지원 영역 안의 유효 위치만 집계한다. */
    public List<MapRegionSummary> findActiveRegions(Instant now, MapQueryBounds supported) {
        Query query = entityManager.createNativeQuery("""
                select g.id, g.name, g.cluster_latitude, g.cluster_longitude,
                       g.south, g.north, g.west, g.east, g.display_order, count(l.id),
                       least(g.south, coalesce(min(l.latitude), g.south)),
                       greatest(g.north, coalesce(max(l.latitude), g.north)),
                       least(g.west, coalesce(min(l.longitude), g.west)),
                       greatest(g.east, coalesce(max(l.longitude), g.east))
                from map_region g
                left join restaurant r on r.map_region_id = g.id and r.deleted = false
                left join restaurant_location l on l.id = r.location_id and
                """ + USABLE_LOCATION + """
                and l.latitude between :south and :north and l.longitude between :west and :east
                where g.active = true
                group by g.id, g.name, g.cluster_latitude, g.cluster_longitude,
                         g.south, g.north, g.west, g.east, g.display_order
                order by g.display_order, g.id
                """).setParameter("now", utc(now))
                .setParameter("south", supported.south()).setParameter("north", supported.north())
                .setParameter("west", supported.west()).setParameter("east", supported.east());
        return rows(query).stream().map(row -> new MapRegionSummary(number(row[0]), (String) row[1],
                (BigDecimal) row[2], (BigDecimal) row[3], bounds(row, 4), ((Number) row[8]).intValue(),
                number(row[9]), bounds(row, 10))).toList();
    }

    private List<RestaurantMapCandidate> candidateRows(MapSearchCriteria criteria, Instant now,
                                                      List<Long> ids, Integer maxResults) {
        StringBuilder sql = matchingRowsSql("""
                select r.id, r.rating, r.review_count
                """, criteria);
        if (ids != null) {
            sql.append(" and r.id in (:ids)");
        }
        sql.append(" order by r.id");
        Query query = bindCriteria(entityManager.createNativeQuery(sql.toString()), criteria, now);
        if (ids != null) {
            query.setParameter("ids", ids);
        }
        if (maxResults != null) {
            query.setMaxResults(maxResults);
        }
        return rows(query).stream().map(row -> new RestaurantMapCandidate(number(row[0]),
                (BigDecimal) row[1], number(row[2]))).toList();
    }

    private static StringBuilder matchingRowsSql(String select, MapSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(select).append("""
                from restaurant r join restaurant_location l on l.id = r.location_id
                where r.deleted = false and
                """).append(USABLE_LOCATION).append("""
                and l.latitude between :south and :north and l.longitude between :west and :east
                """);
        if (criteria.mapRegionId() != null) {
            sql.append(" and r.map_region_id = :regionId")
                    .append(" and exists (select 1 from map_region g where g.id = r.map_region_id and g.active = true)");
        }
        if (criteria.genre() != null) {
            sql.append(" and r.genre = :genre");
        }
        if (criteria.placeType() != null) {
            sql.append(" and r.place_type = :placeType");
        }
        if (criteria.keyword() != null) {
            appendKeywordPredicate(sql, criteria);
        }
        return sql;
    }

    private static Query bindCriteria(Query query, MapSearchCriteria criteria, Instant now) {
        query.setParameter("now", utc(now))
                .setParameter("south", criteria.bounds().south()).setParameter("north", criteria.bounds().north())
                .setParameter("west", criteria.bounds().west()).setParameter("east", criteria.bounds().east());
        if (criteria.mapRegionId() != null) {
            query.setParameter("regionId", criteria.mapRegionId());
        }
        if (criteria.genre() != null) {
            query.setParameter("genre", criteria.genre().name());
        }
        if (criteria.placeType() != null) {
            query.setParameter("placeType", criteria.placeType().name());
        }
        if (criteria.keyword() != null) {
            List<String> patterns = criteria.keywordPatterns();
            for (int index = 0; index < patterns.size(); index++) {
                query.setParameter("keyword" + index, patterns.get(index));
            }
        }
        return query;
    }

    private static void appendKeywordPredicate(StringBuilder sql, MapSearchCriteria criteria) {
        sql.append(" and (");
        List<String> patterns = criteria.keywordPatterns();
        for (int index = 0; index < patterns.size(); index++) {
            if (index > 0) {
                sql.append(" or ");
            }
            String parameter = ":keyword" + index;
            if (criteria.hashtagOnly()) {
                appendHashtagExists(sql, parameter);
            } else {
                sql.append("(lower(r.name) like ").append(parameter).append(" escape '!' or ")
                        .append("exists (select 1 from restaurant_menu m where m.restaurant_id = r.id ")
                        .append("and lower(m.name) like ").append(parameter).append(" escape '!') or ");
                appendHashtagExists(sql, parameter);
                sql.append(")");
            }
        }
        sql.append(")");
    }

    private static void appendHashtagExists(StringBuilder sql, String parameter) {
        sql.append("exists (select 1 from restaurant_hashtag h where h.restaurant_id = r.id ")
                .append("and lower(h.hashtag) like ").append(parameter).append(" escape '!')");
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    private static Long number(Object value) {
        return ((Number) value).longValue();
    }

    private static MapQueryBounds bounds(Object[] row, int start) {
        return new MapQueryBounds((BigDecimal) row[start], (BigDecimal) row[start + 1],
                (BigDecimal) row[start + 2], (BigDecimal) row[start + 3]);
    }

    /** Timestamp 바인딩의 JVM/연결 시간대 변환을 피하고 UTC DATETIME(6)을 직접 비교한다. */
    private static String utc(Instant instant) {
        return UTC_DATETIME.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Instant instant(Object value) {
        return LocalDateTime.parse((String) value).toInstant(ZoneOffset.UTC);
    }

    private static List<AttributionInfo> attributions(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return List.of(ATTRIBUTIONS_MAPPER.readValue((String) value, RestaurantLocationAttribution[].class))
                    .stream().map(item -> new AttributionInfo(item.displayName(), item.uri())).toList();
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            // 출처가 손상된 좌표를 출처 없이 공개하지 않는다. 원문은 로그/예외에 포함하지 않는다.
            throw new DataRetrievalFailureException("Stored location attribution is invalid");
        }
    }
}
