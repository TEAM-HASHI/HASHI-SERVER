package org.sopt.hashi.user.collection.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

/** 저장 식당 조회 전용 — 쓰기는 애그리거트 루트({@link RestaurantCollection})를 통해서만 한다. */
public interface SavedRestaurantRepository extends JpaRepository<SavedRestaurant, Long> {

    /** 여러 컬렉션의 저장 식당을 저장 순서로 한 번에 읽는다 — 목록의 저장 수·커버 계산용(N+1 방지). */
    List<SavedRestaurant> findAllByCollection_IdInOrderByIdAsc(Collection<Long> collectionIds);

    /** 컬렉션 상세의 저장 식당 — 최신 저장 순. */
    List<SavedRestaurant> findAllByCollection_IdOrderByIdDesc(Long collectionId);

    /** 공개 범위와 무관하게 같은 소유자의 여러 저장은 한 번만 센다. */
    @Query("select s.restaurantId as restaurantId, count(distinct s.collection.userId) as saveCount "
            + "from SavedRestaurant s where s.restaurantId in :ids group by s.restaurantId")
    List<SaveCount> countOwnersByRestaurantIds(@Param("ids") Collection<Long> ids);

    @Query("select distinct s.restaurantId from SavedRestaurant s "
            + "where s.collection.userId = :userId and s.restaurantId in :ids")
    List<Long> findSavedRestaurantIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    @Query("select s.restaurantId from SavedRestaurant s where s.collection.id = :collectionId order by s.restaurantId")
    List<Long> findMapRestaurantIds(@Param("collectionId") Long collectionId, Pageable pageable);

    interface SaveCount {
        Long getRestaurantId();
        long getSaveCount();
    }
}
