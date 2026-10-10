package org.sopt.hashi.user.collection.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository.SaveCount;
import org.sopt.hashi.user.collection.dto.MyRestaurantSavesResponse;
import org.sopt.hashi.user.collection.dto.MyRestaurantSavesResponse.MyRestaurantSave;
import org.sopt.hashi.user.collection.dto.RestaurantSaveCountsResponse;
import org.sopt.hashi.user.collection.dto.RestaurantSaveCountsResponse.RestaurantSaveCount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class RestaurantSaveSummaryService {
    private final SavedRestaurantRepository savedRepository;
    private final RestaurantPort restaurantPort;
    private final CurrentUserProvider currentUserProvider;

    public RestaurantSaveSummaryService(SavedRestaurantRepository savedRepository,
                                        RestaurantPort restaurantPort, CurrentUserProvider currentUserProvider) {
        this.savedRepository = savedRepository;
        this.restaurantPort = restaurantPort;
        this.currentUserProvider = currentUserProvider;
    }

    public RestaurantSaveCountsResponse getSaveCounts(List<Long> restaurantIds) {
        List<Long> ids = activeIds(restaurantIds);
        if (ids.isEmpty()) {
            return new RestaurantSaveCountsResponse(List.of());
        }
        Map<Long, Long> counts = savedRepository.countOwnersByRestaurantIds(ids).stream()
                .collect(Collectors.toMap(SaveCount::getRestaurantId, SaveCount::getSaveCount));
        return new RestaurantSaveCountsResponse(ids.stream()
                .map(id -> new RestaurantSaveCount(id, counts.getOrDefault(id, 0L))).toList());
    }

    public MyRestaurantSavesResponse getMySaves(List<Long> restaurantIds) {
        if (!currentUserProvider.isAuthenticatedUser()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        Long userId = currentUserProvider.currentUserId();
        List<Long> ids = activeIds(restaurantIds);
        Set<Long> saved = ids.isEmpty() ? Set.of()
                : new HashSet<>(savedRepository.findSavedRestaurantIds(userId, ids));
        return new MyRestaurantSavesResponse(ids.stream()
                .map(id -> new MyRestaurantSave(id, saved.contains(id))).toList());
    }

    private List<Long> activeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        // Port 실패는 그대로 실패한다. 삭제/미존재와 provider 장애를 0 또는 false로 혼동하지 않는다.
        Set<Long> active = restaurantPort.findActiveCards(ids).stream()
                .map(RestaurantCardInfo::id).collect(Collectors.toSet());
        return ids.stream().filter(active::contains).toList();
    }
}
