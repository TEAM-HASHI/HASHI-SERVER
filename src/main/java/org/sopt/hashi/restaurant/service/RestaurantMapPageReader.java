package org.sopt.hashi.restaurant.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
        List<Long> remainingIds = ordered.subList(start, ordered.size()).stream()
                .map(RestaurantMapCandidate::restaurantId).toList();
        var matching = mapService.findMatchingCandidates(session.criteria(), remainingIds).stream()
                .map(RestaurantMapCandidate::restaurantId).collect(Collectors.toSet());
        var infos = mapService.findActiveMapInfos(remainingIds.stream().filter(matching::contains).toList()).stream()
                .filter(info -> info.location() != null && clock.instant().isBefore(info.location().validUntil()))
                .collect(Collectors.toMap(RestaurantMapInfo::restaurantId, Function.identity()));
        List<RestaurantMapCandidate> selected = new ArrayList<>();
        int nextPosition = start;
        boolean hasNext = false;
        for (int position = start; position < ordered.size(); position++) {
            RestaurantMapCandidate candidate = ordered.get(position);
            if (!infos.containsKey(candidate.restaurantId())) {
                continue;
            }
            if (selected.size() == PAGE_SIZE) {
                hasNext = true;
                break;
            }
            selected.add(candidate);
            // lookahead한 11번째 후보는 소비하지 않는다. 이전 cursor 앞의 복구 후보도 재삽입하지 않는다.
            nextPosition = position + 1;
        }
        return new Page(restaurants.findMapCards(selected, infos), hasNext, nextPosition);
    }

    public record Page(List<MapCardResponse> content, boolean hasNext, int nextPosition) {
    }
}
