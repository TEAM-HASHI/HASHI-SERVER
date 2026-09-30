package org.sopt.hashi.user.collection.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.dto.CollectionMapMarkersResponse;
import org.sopt.hashi.user.collection.dto.CollectionMapMarkersResponse.Marker;
import org.sopt.hashi.user.collection.dto.CollectionMapMarkersResponse.Position;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** DB snapshot와 최종 검사 사이의 Port 호출에는 user transaction/부모 잠금을 유지하지 않는다. */
@Service
public class CollectionMapQueryService {
    private final CollectionMapSnapshotStore snapshots;
    private final RestaurantPort restaurants;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public CollectionMapQueryService(CollectionMapSnapshotStore snapshots, RestaurantPort restaurants,
                                     CurrentUserProvider currentUserProvider, @Qualifier("japanClock") Clock clock) {
        this.snapshots = snapshots;
        this.restaurants = restaurants;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    public CollectionMapMarkersResponse getMarkers(Long collectionId) {
        if (collectionId == null || collectionId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        try {
            return readMarkers(collectionId);
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(UserErrorCode.COLLECTION_MAP_UNAVAILABLE, exception);
        }
    }

    private CollectionMapMarkersResponse readMarkers(Long collectionId) {
        Long viewerId = currentUserProvider.isAuthenticatedUser() ? currentUserProvider.currentUserId() : null;
        var snapshot = snapshots.read(collectionId, viewerId);
        List<RestaurantMapInfo> infos = snapshot.restaurantIds().isEmpty() ? List.of()
                : findMapInfos(snapshot.restaurantIds());
        // 변경됐거나 접근할 수 없으면 응답을 만들지 않는다. 일부 batch 성공도 반환하지 않는다.
        snapshots.validate(snapshot, viewerId);
        Instant now = clock.instant();
        List<Marker> markers = infos.stream()
                .filter(info -> info.location() != null && now.isBefore(info.location().validUntil()))
                .sorted(Comparator.comparing(RestaurantMapInfo::restaurantId))
                .map(info -> new Marker(info.restaurantId(), info.name(), info.placeType(), info.genre(),
                        new Position(info.location().latitude(), info.location().longitude(), info.location().validUntil())))
                .toList();
        return new CollectionMapMarkersResponse(collectionId, snapshot.version(), now, infos.size(),
                infos.size() - markers.size(), markers);
    }

    private List<RestaurantMapInfo> findMapInfos(List<Long> ids) {
        try {
            return restaurants.findActiveMapInfos(ids);
        } catch (RuntimeException exception) {
            throw new BusinessException(UserErrorCode.COLLECTION_MAP_UNAVAILABLE, exception);
        }
    }
}
