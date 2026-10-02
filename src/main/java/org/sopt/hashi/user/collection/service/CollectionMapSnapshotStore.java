package org.sopt.hashi.user.collection.service;

import java.util.List;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 지도 외부 enrich 동안 DB 잠금을 유지하지 않는다. 두 메서드는 별도 proxy/transaction 경계다. */
@Service
public class CollectionMapSnapshotStore {
    private final RestaurantCollectionRepository collections;
    private final SavedRestaurantRepository saved;

    public CollectionMapSnapshotStore(RestaurantCollectionRepository collections, SavedRestaurantRepository saved) {
        this.collections = collections;
        this.saved = saved;
    }

    /** 부모 변경번호와 자식 목록은 같은 RR snapshot에서 읽는다. 상한+1만 읽어 과량도 bounded다. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(Long id, Long viewerId) {
        RestaurantCollection collection = collections.findById(id)
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
        requireVisible(collection, viewerId);
        List<Long> ids = saved.findMapRestaurantIds(id,
                PageRequest.of(0, RestaurantCollectionService.MAX_SAVED_RESTAURANTS_PER_COLLECTION + 1));
        if (ids.size() > RestaurantCollectionService.MAX_SAVED_RESTAURANTS_PER_COLLECTION) {
            throw new BusinessException(UserErrorCode.COLLECTION_MAP_UNAVAILABLE);
        }
        return new Snapshot(id, collection.getCollectionVersion(), List.copyOf(ids));
    }

    /**
     * 새 RC transaction의 current locking read로 접근권한·변경번호를 최종 검사한다.
     * 검사 중에는 쓰기와 동일한 부모 잠금을 잡는다. 반환 이후 미래 변경까지 차단하지는 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void validate(Snapshot snapshot, Long viewerId) {
        var current = collections.findCurrentMapState(snapshot.collectionId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
        if (!"PUBLIC".equals(current.getVisibility())
                && (viewerId == null || !current.getUserId().equals(viewerId))) {
            throw new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND);
        }
        if (current.getCollectionVersion() != snapshot.version()) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
    }

    private void requireVisible(RestaurantCollection collection, Long viewerId) {
        if (!collection.isPublic() && (viewerId == null || !collection.isOwnedBy(viewerId))) {
            throw new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND);
        }
    }

    public record Snapshot(Long collectionId, long version, List<Long> restaurantIds) { }
}
