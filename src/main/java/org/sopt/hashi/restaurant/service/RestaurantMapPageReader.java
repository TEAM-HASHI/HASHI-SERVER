package org.sopt.hashi.restaurant.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse.MapCardResponse;
import org.sopt.hashi.restaurant.internal.map.MapQuerySession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 한 페이지의 조건 재검사와 카드 구성을 짧은 DB 읽기로 묶는다. Redis는 호출하지 않는다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RestaurantMapPageReader {
    private static final int PAGE_SIZE = 10;
    private static final int SCAN_BATCH_SIZE = 32;
    private final RestaurantMapService mapService;
    private final RestaurantService restaurants;
    private final Clock clock;

    public RestaurantMapPageReader(RestaurantMapService mapService, RestaurantService restaurants,
                                   @Qualifier("japanClock") Clock clock) {
        this.mapService = mapService;
        this.restaurants = restaurants;
        this.clock = clock;
    }

    public Page read(MapQuerySession session, RestaurantMapSort sort, int start) {
        List<RestaurantMapCandidate> ordered = session.ordered(sort);
        var infos = new HashMap<Long, RestaurantMapInfo>();
        List<RestaurantMapCandidate> selected = new ArrayList<>();
        int nextPosition = start;
        boolean hasNext = false;
        for (int batchStart = start; batchStart < ordered.size() && !hasNext; batchStart += SCAN_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + SCAN_BATCH_SIZE, ordered.size());
            var batchIds = ordered.subList(batchStart, batchEnd).stream()
                    .map(RestaurantMapCandidate::restaurantId).toList();
            var matching = mapService.findMatchingCandidates(session.criteria(), batchIds).stream()
                    .map(RestaurantMapCandidate::restaurantId).toList();
            var batchInfos = mapService.findActiveMapInfos(matching).stream()
                    .filter(info -> info.location() != null && clock.instant().isBefore(info.location().validUntil()))
                    .collect(Collectors.toMap(RestaurantMapInfo::restaurantId, Function.identity()));
            for (int position = batchStart; position < batchEnd; position++) {
                var candidate = ordered.get(position);
                var info = batchInfos.get(candidate.restaurantId());
                if (info != null && selected.size() == PAGE_SIZE) {
                    hasNext = true;
                    // 11번째 유효 후보는 아직 소비하지 않는다.
                    nextPosition = position;
                    break;
                }
                nextPosition = position + 1;
                if (info != null) {
                    selected.add(candidate);
                    infos.put(candidate.restaurantId(), info);
                }
            }
        }
        return new Page(restaurants.findMapCards(selected, infos), hasNext, nextPosition);
    }

    public record Page(List<MapCardResponse> content, boolean hasNext, int nextPosition) {
    }
}
