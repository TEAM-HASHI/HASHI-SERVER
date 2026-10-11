package org.sopt.hashi.user.collection.domain;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantCollectionRepository extends JpaRepository<RestaurantCollection, Long> {

    /** 내 컬렉션 목록 첫 페이지 — 생성일 최신순(id 역순). 컬렉션 목록 정렬 옵션은 기획 미확정이라 이 순서만 제공한다. */
    List<RestaurantCollection> findAllByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 내 컬렉션 목록 다음 페이지 — 커서(마지막 컬렉션 id)보다 오래된 컬렉션. */
    List<RestaurantCollection> findAllByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long id, Pageable pageable);

    boolean existsByUserIdAndName(Long userId, String name);

    boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);

    long countByUserId(Long userId);

    /** 쓰기 경로 모두 같은 부모 행을 먼저 잠가 membership과 상한 검사를 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RestaurantCollection c where c.id = :id")
    Optional<RestaurantCollection> findForUpdate(@Param("id") Long id);

    /** Native scalar current read는 OSIV/1차 캐시의 기존 Entity 값을 재사용하지 않는다. */
    @Query(value = "select user_id as userId, visibility as visibility, collection_version as collectionVersion "
            + "from restaurant_collection where id = :id for update", nativeQuery = true)
    Optional<CurrentMapState> findCurrentMapState(@Param("id") Long id);

    interface CurrentMapState {
        Long getUserId();
        String getVisibility();
        long getCollectionVersion();
    }

    /** 탈퇴 정리 — 회원의 모든 컬렉션에 저장된 식당 매핑을 한 번에 지운다(컬렉션 삭제 전에 호출, 애그리거트 내부 FK). */
    @Modifying
    @Query("delete from SavedRestaurant s where s.collection.id in "
            + "(select c.id from RestaurantCollection c where c.userId = :userId)")
    void deleteSavedRestaurantsByUserId(@Param("userId") Long userId);

    /** 탈퇴 정리 — 회원의 모든 컬렉션을 한 번에 지운다. */
    @Modifying
    @Query("delete from RestaurantCollection c where c.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
