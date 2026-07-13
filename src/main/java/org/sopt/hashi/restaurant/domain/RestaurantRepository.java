package org.sopt.hashi.restaurant.domain;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

    Optional<Restaurant> findByIdAndDeletedFalse(Long id);

    boolean existsByIdAndDeletedFalse(Long id);

    @EntityGraph(attributePaths = "businessHours")
    @Query("select distinct r from Restaurant r where r.id = :restaurantId and r.deleted = false")
    Optional<Restaurant> findActiveByIdWithBusinessHours(@Param("restaurantId") Long restaurantId);

    @EntityGraph(attributePaths = "images")
    @Query("select distinct r from Restaurant r where r.id = :restaurantId and r.deleted = false")
    Optional<Restaurant> findActiveByIdWithImages(@Param("restaurantId") Long restaurantId);

    @Query(value = """
            select r.id
            from restaurant r
            join restaurant_curation_type rct on rct.restaurant_id = r.id
            where r.deleted = false
                and rct.curation_type = :curationType
                and (:excludeRestaurantId is null or r.id <> :excludeRestaurantId)
            order by rand()
            limit 1
            """, nativeQuery = true)
    Optional<Long> findRandomRestaurantIdByCurationTypeExcluding(
            @Param("curationType") String curationType,
            @Param("excludeRestaurantId") Long excludeRestaurantId
    );

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
                and (:cursor is null or m.id < :cursor)
            order by m.id desc
            """)
    List<RestaurantMenu> findMenusByRestaurantId(
            @Param("restaurantId") Long restaurantId,
            @Param("cursor") Long cursor,
            Pageable pageable
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
