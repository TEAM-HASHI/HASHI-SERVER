package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent.ResultBounds;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;

/** Redis에는 조회 조건, 최초 추천 순서/순위 값, 검색 결과의 집계 경계만 저장한다. 개별 좌표·카드·개인 상태는 없다. */
public record MapQuerySession(int schemaVersion, UUID id, MapSearchCriteria criteria,
                              List<RestaurantMapCandidate> candidates, MapSearchResultExtent searchResult,
                              Instant rankingAsOf, Instant expiresAt) {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_CANDIDATES = 4_194_304 / 32;
    public static final int MAX_BYTES = 4_194_304;
    public static final Duration LIFETIME = Duration.ofMinutes(30);

    public MapQuerySession {
        if (schemaVersion != SCHEMA_VERSION || id == null || criteria == null || candidates == null
                || candidates.size() > MAX_CANDIDATES || rankingAsOf == null || expiresAt == null) {
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
        if (!validSearchResult(criteria, candidates.size(), searchResult, rankingAsOf, expiresAt)) {
            throw new IllegalArgumentException("Invalid map search result");
        }
        candidates = List.copyOf(candidates);
    }

    private static boolean validSearchResult(MapSearchCriteria criteria, int candidateCount,
                                             MapSearchResultExtent result, Instant rankingAsOf, Instant expiresAt) {
        if (criteria.keyword() == null) {
            return result == null;
        }
        if (result == null || result.totalCount() != candidateCount) {
            return false;
        }
        if (result.bounds() == null) {
            return result.totalCount() == 0;
        }
        return contains(criteria, result.bounds()) && result.earliestValidUntil().isAfter(rankingAsOf)
                && !expiresAt.isAfter(result.earliestValidUntil());
    }

    private static boolean contains(MapSearchCriteria criteria, ResultBounds result) {
        var query = criteria.bounds();
        return result.south().compareTo(query.south()) >= 0 && result.north().compareTo(query.north()) <= 0
                && result.west().compareTo(query.west()) >= 0 && result.east().compareTo(query.east()) <= 0;
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
