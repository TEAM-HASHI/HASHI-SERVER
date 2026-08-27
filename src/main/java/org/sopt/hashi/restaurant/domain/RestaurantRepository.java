package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.LockModeType;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

    Optional<Restaurant> findByIdAndDeletedFalse(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select restaurant from Restaurant restaurant where restaurant.id = :restaurantId")
    Optional<Restaurant> findByIdForUpdate(@Param("restaurantId") Long restaurantId);

    boolean existsByIdAndDeletedFalse(Long id);

    @EntityGraph(attributePaths = "businessHours")
    @Query("select distinct r from Restaurant r where r.id = :restaurantId and r.deleted = false")
    Optional<Restaurant> findActiveByIdWithBusinessHours(@Param("restaurantId") Long restaurantId);

    @EntityGraph(attributePaths = "images")
    @Query("select distinct r from Restaurant r where r.id = :restaurantId and r.deleted = false")
    Optional<Restaurant> findActiveByIdWithImages(@Param("restaurantId") Long restaurantId);

    // 랜덤 추천 — 큐레이션 조건 없이 전체 활성 식당에서 뽑는다(#154). rand() 정렬은 식당 수가 적은 규모를 전제로 한다.
    @Query(value = """
            select r.id
            from restaurant r
            where r.deleted = false
                and (:excludeRestaurantId is null or r.id <> :excludeRestaurantId)
            order by rand()
            limit 1
            """, nativeQuery = true)
    Optional<Long> findRandomRestaurantIdExcluding(@Param("excludeRestaurantId") Long excludeRestaurantId);

    @Query("""
            select businessHour
            from RestaurantBusinessHour businessHour
            join fetch businessHour.restaurant restaurant
            where restaurant.id in :restaurantIds
                and restaurant.deleted = false
                and businessHour.dayOfWeek = :dayOfWeek
            """)
    List<RestaurantBusinessHour> findBusinessHoursByRestaurantIdsAndDayOfWeek(
            @Param("restaurantIds") List<Long> restaurantIds,
            @Param("dayOfWeek") DayOfWeek dayOfWeek
    );

    @Query("""
            select m from RestaurantMenu m
            where m.restaurant.id = :restaurantId
                and m.restaurant.deleted = false
                and (:excludeMenuId is null or m.id <> :excludeMenuId)
                and (
                    :cursorId is null
                    or (
                        :cursorMain = true
                        and (
                            m.main = false
                            or (
                                m.main = true
                                and (
                                    m.name > :cursorName
                                    or (m.name = :cursorName and m.id < :cursorId)
                                )
                            )
                        )
                    )
                    or (
                        :cursorMain = false
                        and m.main = false
                        and m.id < :cursorId
                    )
                )
            order by
                case when m.main = true then 0 else 1 end asc,
                case when m.main = true then m.name else '' end asc,
                m.id desc
            """)
    List<RestaurantMenu> findMenusByRestaurantId(
            @Param("restaurantId") Long restaurantId,
            @Param("excludeMenuId") Long excludeMenuId,
            @Param("cursorMain") Boolean cursorMain,
            @Param("cursorName") String cursorName,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Query("""
            select m from RestaurantMenu m
            where m.restaurant.id = :restaurantId
                and m.restaurant.deleted = false
                and m.id = :menuId
            """)
    Optional<RestaurantMenu> findMenuByRestaurantIdAndMenuId(
            @Param("restaurantId") Long restaurantId,
            @Param("menuId") Long menuId
    );

    @Query("""
            select count(m) from RestaurantMenu m
            where m.restaurant.id = :restaurantId
                and m.restaurant.deleted = false
                and m.id <> :menuId
            """)
    long countOtherMenusByRestaurantId(
            @Param("restaurantId") Long restaurantId,
            @Param("menuId") Long menuId
    );

    @Query("""
            select distinct r.name
            from Restaurant r
            where r.deleted = false
                and lower(r.name) like lower(concat('%', :keyword, '%'))
            order by r.name asc
            """)
    List<String> findRestaurantSuggestionKeywords(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select distinct m.name
            from Restaurant r
            join r.menus m
            where r.deleted = false
                and lower(m.name) like lower(concat('%', :keyword, '%'))
            order by m.name asc
            """)
    List<String> findMenuSuggestionKeywords(@Param("keyword") String keyword, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update restaurant
            set rating = round((rating_sum + :rating) / (review_count + 1), 1),
                rating_sum = rating_sum + :rating,
                review_count = review_count + 1
            where id = :restaurantId
            """, nativeQuery = true)
    int increaseReviewStatistics(@Param("restaurantId") Long restaurantId, @Param("rating") int rating);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update restaurant
            set rating = case
                    when review_count <= 1 then 0.0
                    else round((rating_sum - :rating) / (review_count - 1), 1)
                end,
                rating_sum = greatest(rating_sum - :rating, 0),
                review_count = greatest(review_count - 1, 0)
            where id = :restaurantId
              and review_count > 0
              and rating_sum >= :rating
            """, nativeQuery = true)
    int decreaseReviewStatistics(@Param("restaurantId") Long restaurantId, @Param("rating") int rating);
}
