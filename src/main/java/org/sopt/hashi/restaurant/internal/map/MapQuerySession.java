package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;

/** Redis에는 조회 조건과 최초 추천 순서/순위 값만 저장한다. 좌표·카드·개인 상태는 없다. */
public record MapQuerySession(int schemaVersion, UUID id, MapSearchCriteria criteria,
                              List<RestaurantMapCandidate> candidates, Instant rankingAsOf, Instant expiresAt) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_CANDIDATES = 500;
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_SESSIONS = 128;
    public static final Duration LIFETIME = Duration.ofMinutes(15);

    public MapQuerySession {
        if (schemaVersion != SCHEMA_VERSION || id == null || criteria == null || candidates == null
                || candidates.size() > MAX_CANDIDATES || rankingAsOf == null || expiresAt == null
                || !rankingAsOf.isBefore(expiresAt)) {
            throw new IllegalArgumentException("Invalid map session");
        }
        var ids = new HashSet<Long>();
        for (var candidate : candidates) {
            if (candidate == null || candidate.restaurantId() == null || candidate.restaurantId() <= 0
                    || candidate.rating() == null || candidate.rating().signum() < 0 || candidate.reviewCount() < 0
                    || !ids.add(candidate.restaurantId())) {
                throw new IllegalArgumentException("Invalid map candidate");
            }
        }
        candidates = List.copyOf(candidates);
    }

    /** Stream의 안정 정렬이 최초 추천 순서를 동점 기준으로 보존한다. */
    public List<RestaurantMapCandidate> ordered(RestaurantMapSort sort) {
        return switch (sort) {
            case RECOMMEND -> candidates;
            case RATING -> candidates.stream().sorted(Comparator.comparing(RestaurantMapCandidate::rating)
                    .reversed()).toList();
            case REVIEWS -> candidates.stream().sorted(Comparator.comparingLong(RestaurantMapCandidate::reviewCount)
                    .reversed()).toList();
        };
    }

    @Override
    public String toString() {
        return "MapQuerySession[version=" + schemaVersion + ", candidates=" + candidates.size() + "]";
    }
}
