package org.sopt.hashi.user.collection.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantCollectionRepository extends JpaRepository<RestaurantCollection, Long> {

    /** 내 컬렉션 목록 첫 페이지 — 생성일 최신순(id 역순). 컬렉션 목록 정렬 옵션은 기획 미확정이라 이 순서만 제공한다. */
    List<RestaurantCollection> findAllByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 내 컬렉션 목록 다음 페이지 — 커서(마지막 컬렉션 id)보다 오래된 컬렉션. */
    List<RestaurantCollection> findAllByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long id, Pageable pageable);

    boolean existsByUserIdAndName(Long userId, String name);

    boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);

    long countByUserId(Long userId);
}
