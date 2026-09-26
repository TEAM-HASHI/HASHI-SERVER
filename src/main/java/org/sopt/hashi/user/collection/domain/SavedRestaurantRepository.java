package org.sopt.hashi.user.collection.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 저장 식당 조회 전용 — 쓰기는 애그리거트 루트({@link RestaurantCollection})를 통해서만 한다. */
public interface SavedRestaurantRepository extends JpaRepository<SavedRestaurant, Long> {

    /** 여러 컬렉션의 저장 식당을 저장 순서로 한 번에 읽는다 — 목록의 저장 수·커버 계산용(N+1 방지). */
    List<SavedRestaurant> findAllByCollection_IdInOrderByIdAsc(Collection<Long> collectionIds);

    /** 컬렉션 상세의 저장 식당 — 최신 저장 순. */
    List<SavedRestaurant> findAllByCollection_IdOrderByIdDesc(Long collectionId);
}
