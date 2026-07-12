package org.sopt.hashi.review.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {

    @Query("""
            select image
            from ReviewImage image
            where image.id = :imageId
              and image.review.restaurantId = :restaurantId
              and image.review.deleted = false
            """)
    Optional<ReviewImage> findActiveByIdAndRestaurantId(
            @Param("imageId") Long imageId,
            @Param("restaurantId") Long restaurantId
    );

    @Query("""
            select image
            from ReviewImage image
            where image.review.restaurantId = :restaurantId
              and image.review.deleted = false
              and (:cursor is null or image.id < :cursor)
            order by image.id desc
            """)
    List<ReviewImage> findPageByRestaurantId(
            @Param("restaurantId") Long restaurantId,
            @Param("cursor") Long cursor,
            Pageable pageable
    );
}
