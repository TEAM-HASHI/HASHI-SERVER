package org.sopt.hashi.restaurant.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

    Optional<Restaurant> findByIdAndActiveTrue(Long id);

    boolean existsByIdAndActiveTrue(Long id);

    @EntityGraph(attributePaths = "businessHours")
    @Query("select distinct r from Restaurant r where r.id = :restaurantId and r.active = true")
    Optional<Restaurant> findActiveByIdWithBusinessHours(@Param("restaurantId") Long restaurantId);

    @Query("""
            select m from RestaurantMenu m
            where m.restaurant.id = :restaurantId
                and m.restaurant.active = true
                and (:cursor is null or m.id < :cursor)
            order by m.id desc
            """)
    List<RestaurantMenu> findMenusByRestaurantId(
            @Param("restaurantId") Long restaurantId,
            @Param("cursor") Long cursor,
            Pageable pageable
    );
}
