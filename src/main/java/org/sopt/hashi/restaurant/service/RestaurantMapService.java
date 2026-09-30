package org.sopt.hashi.restaurant.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapRegionSummary;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapQueryRepository;
import org.sopt.hashi.restaurant.dto.RestaurantMapLocationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse.BoundsResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse.PositionResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse.QueryLimitsResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapRegionsResponse.RegionResponse;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class RestaurantMapService {

    private static final int ID_BATCH_SIZE = 500;

    private final RestaurantMapQueryRepository repository;
    private final MapQueryProperties properties;
    private final Clock clock;

    public RestaurantMapService(RestaurantMapQueryRepository repository, MapQueryProperties properties,
                                @Qualifier("japanClock") Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public RestaurantMapRegionsResponse getRegions() {
        var configuration = properties.requireConfiguration();
        List<MapRegionSummary> regions = read(() -> repository.findActiveRegions(clock.instant()));
        if (regions.isEmpty()) {
            throw new BusinessException(RestaurantErrorCode.MAP_CONFIGURATION_UNAVAILABLE);
        }
        for (MapRegionSummary region : regions) {
            validateRegionConfiguration(region.bounds(), configuration.supportedBounds());
            if (region.outsideBoundsCount() > 0) {
                log.warn("Map region mapping outside bounds: regionId={}, count={}",
                        region.id(), region.outsideBoundsCount());
            }
        }
        return new RestaurantMapRegionsResponse(BoundsResponse.from(configuration.initialBounds()),
                new QueryLimitsResponse(BoundsResponse.from(configuration.supportedBounds()), 1, 1),
                regions.stream().map(region -> new RegionResponse(region.id(), region.name(),
                        region.restaurantCount(), new PositionResponse(region.latitude(), region.longitude()),
                        BoundsResponse.from(region.bounds()), region.displayOrder())).toList());
    }

    public RestaurantMapLocationResponse getLocation(Long restaurantId) {
        List<RestaurantMapInfo> infos = findActiveMapInfos(List.of(restaurantId));
        RestaurantMapInfo info = infos.stream().findFirst()
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
        if (info.location() == null) {
            throw new BusinessException(RestaurantErrorCode.MAP_LOCATION_UNAVAILABLE);
        }
        return new RestaurantMapLocationResponse(info.restaurantId(), info.location());
    }

    /** capacity는 후속 세션 담당자가 측정해 전달한다. 초과 후보 목록은 반환하지 않는다. */
    public CandidateSnapshot findCandidates(MapSearchCriteria criteria, int capacity) {
        if (capacity < 1 || capacity == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("후보 상한은 1 이상이고 상한 + 1을 조회할 수 있어야 합니다");
        }
        validateCriteria(criteria);
        Instant rankingAsOf = clock.instant();
        List<RestaurantMapCandidate> candidates = read(
                () -> repository.findCandidates(criteria, rankingAsOf, capacity + 1));
        if (candidates.size() > capacity) {
            throw new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        }
        return new CandidateSnapshot(candidates, rankingAsOf);
    }

    /** 현재 조건을 재검사한 ID만 첫 요청 순서로 반환한다. 새 순위 값은 세션의 고정 순위를 대체하지 않는다. */
    public List<RestaurantMapCandidate> findMatchingCandidates(MapSearchCriteria criteria, Collection<Long> restaurantIds) {
        List<Long> ids = normalizedIds(restaurantIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        validateCriteria(criteria);
        Instant now = clock.instant();
        return readBatches(ids, batch -> repository.findMatchingCandidates(criteria, now, batch),
                RestaurantMapCandidate::restaurantId);
    }

    /** 지도 설정과 독립적이다. 위치가 없어도 공개 식당을 반환해야 컬렉션 목록을 유지할 수 있다. */
    public List<RestaurantMapInfo> findActiveMapInfos(Collection<Long> restaurantIds) {
        List<Long> ids = normalizedIds(restaurantIds);
        Instant now = clock.instant();
        return readBatches(ids, batch -> repository.findActiveMapInfos(batch, now), RestaurantMapInfo::restaurantId);
    }

    public record CandidateSnapshot(List<RestaurantMapCandidate> candidates, Instant rankingAsOf) {
        public CandidateSnapshot {
            candidates = List.copyOf(candidates);
        }
    }

    private void validateCriteria(MapSearchCriteria criteria) {
        if (criteria == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var configuration = properties.requireConfiguration();
        criteria.bounds().validateQueryWithin(configuration.supportedBounds());
        if (criteria.mapRegionId() != null) {
            MapQueryBounds region = read(() -> repository.findActiveRegionBounds(criteria.mapRegionId()))
                    .orElseThrow(() -> new BusinessException(RestaurantErrorCode.MAP_REGION_INVALID));
            validateRegionConfiguration(region, configuration.supportedBounds());
        }
    }

    private void validateRegionConfiguration(MapQueryBounds bounds, MapQueryBounds supported) {
        try {
            bounds.validateQueryWithin(supported);
        } catch (BusinessException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_CONFIGURATION_UNAVAILABLE);
        }
    }

    private static List<Long> normalizedIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("식당 ID는 양수여야 합니다");
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private <T> List<T> readBatches(List<Long> ids, Function<List<Long>, List<T>> fetch, Function<T, Long> id) {
        List<T> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += ID_BATCH_SIZE) {
            List<Long> batch = ids.subList(start, Math.min(start + ID_BATCH_SIZE, ids.size()));
            result.addAll(read(() -> fetch.apply(batch)));
        }
        Map<Long, T> byId = result.stream().collect(Collectors.toMap(id, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_QUERY_UNAVAILABLE, exception);
        }
    }
}
