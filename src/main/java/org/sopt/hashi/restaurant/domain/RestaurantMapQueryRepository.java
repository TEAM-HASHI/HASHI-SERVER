package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;
import org.springframework.stereotype.Repository;

/** 지도 전용 값 projection. 모든 SQL은 restaurant 모듈 안의 테이블만 사용한다. */
@Repository
public class RestaurantMapQueryRepository {

    private static final DateTimeFormatter UTC_DATETIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");
    private static final String USABLE_LOCATION = """
            l.status = 'READY' and l.latitude is not null and l.longitude is not null
            and l.latitude between -90 and 90 and l.longitude between -180 and 180
            and l.valid_until > cast(:now as datetime(6))
            """;
    private static final String REGION_CONTAINS_LOCATION = """
            l.latitude between g.south and g.north and l.longitude between g.west and g.east
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
                       date_format(l.valid_until, '%Y-%m-%dT%H:%i:%s.%f')
                from restaurant r left join restaurant_location l on l.id = r.location_id and
                """ + USABLE_LOCATION + " where r.deleted = false and r.id in (:ids)")
                .setParameter("ids", ids).setParameter("now", utc(now));
        return rows(query).stream().map(row -> new RestaurantMapInfo(number(row[0]), (String) row[1],
                RestaurantPlaceType.valueOf((String) row[2]).value(),
                RestaurantGenre.valueOf((String) row[3]).value(),
                row[4] == null ? null : new LocationInfo((BigDecimal) row[4], (BigDecimal) row[5], instant(row[6]))))
                .toList();
    }

    /** 활성 지역은 0건도 포함한다. 범위 밖 매핑 수는 잘못된 운영 설정을 식별하는 데 사용한다. */
    public List<MapRegionSummary> findActiveRegions(Instant now) {
        Query query = entityManager.createNativeQuery("""
                select g.id, g.name, g.cluster_latitude, g.cluster_longitude,
                       g.south, g.north, g.west, g.east, g.display_order,
                """ + "sum(case when " + REGION_CONTAINS_LOCATION + " then 1 else 0 end), "
                + "sum(case when l.id is not null and not (" + REGION_CONTAINS_LOCATION
                + ") then 1 else 0 end) " + """
                from map_region g
                left join restaurant r on r.map_region_id = g.id and r.deleted = false
                left join restaurant_location l on l.id = r.location_id and
                """ + USABLE_LOCATION + """
                where g.active = true
                group by g.id, g.name, g.cluster_latitude, g.cluster_longitude,
                         g.south, g.north, g.west, g.east, g.display_order
                order by g.display_order, g.id
                """).setParameter("now", utc(now));
        return rows(query).stream().map(row -> new MapRegionSummary(number(row[0]), (String) row[1],
                (BigDecimal) row[2], (BigDecimal) row[3], bounds(row, 4), ((Number) row[8]).intValue(),
                number(row[9]), number(row[10]))).toList();
    }

    private List<RestaurantMapCandidate> candidateRows(MapSearchCriteria criteria, Instant now,
                                                      List<Long> ids, Integer maxResults) {
        StringBuilder sql = new StringBuilder("""
                select r.id, r.rating, r.review_count
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
            sql.append("""
                     and (lower(r.name) like :keyword escape '!'
                     or exists (select 1 from restaurant_menu m where m.restaurant_id = r.id
                                and lower(m.name) like :keyword escape '!'))
                    """);
        }
        if (ids != null) {
            sql.append(" and r.id in (:ids)");
        }
        sql.append(" order by r.id");
        Query query = entityManager.createNativeQuery(sql.toString()).setParameter("now", utc(now))
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
            query.setParameter("keyword", criteria.keywordPattern());
        }
        if (ids != null) {
            query.setParameter("ids", ids);
        }
        if (maxResults != null) {
            query.setMaxResults(maxResults);
        }
        return rows(query).stream().map(row -> new RestaurantMapCandidate(number(row[0]),
                (BigDecimal) row[1], number(row[2]))).toList();
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
}
