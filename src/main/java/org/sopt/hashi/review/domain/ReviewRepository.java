package org.sopt.hashi.review.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationIdAndActiveTrue(Long reservationId);

    Optional<Review> findByReservationIdAndActiveTrue(Long reservationId);

    List<Review> findByReservationIdInAndActiveTrue(Collection<Long> reservationIds);

    Optional<Review> findByIdAndWriterIdAndActiveTrue(Long reviewId, Long writerId);

    long countByWriterIdAndActiveTrue(Long writerId);

    List<Review> findByWriterIdAndActiveTrueOrderByIdDesc(Long writerId, Pageable pageable);

    List<Review> findByWriterIdAndActiveTrueAndIdLessThanOrderByIdDesc(
            Long writerId, Long cursor, Pageable pageable);

    Optional<Review> findByIdAndRestaurantIdAndActiveTrue(Long id, Long restaurantId);

    long countByRestaurantIdAndActiveTrue(Long restaurantId);

    @Query("""
            select avg(r.rating.value)
            from Review r
            where r.restaurantId = :restaurantId
              and r.active = true
            """)
    Double averageRatingByRestaurantId(@Param("restaurantId") Long restaurantId);

    @Query("""
            select r.rating.value as rating, count(r) as count
            from Review r
            where r.restaurantId = :restaurantId
              and r.active = true
            group by r.rating.value
            """)
    List<RatingCount> countByRating(@Param("restaurantId") Long restaurantId);

    @Query("""
            select r
            from Review r
            where r.restaurantId = :restaurantId
              and r.active = true
              and (:cursorCreatedAt is null
                   or r.createdAt < :cursorCreatedAt
                   or (r.createdAt = :cursorCreatedAt and r.id < :cursorId))
            order by r.createdAt desc, r.id desc
            """)
    List<Review> findLatestPage(
            @Param("restaurantId") Long restaurantId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Query("""
            select r
            from Review r
            where r.restaurantId = :restaurantId
              and r.active = true
              and (:cursorRating is null
                   or r.rating.value < :cursorRating
                   or (r.rating.value = :cursorRating and r.id < :cursorId))
            order by r.rating.value desc, r.id desc
            """)
    List<Review> findRatingHighPage(
            @Param("restaurantId") Long restaurantId,
            @Param("cursorRating") Integer cursorRating,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Query("""
            select r
            from Review r
            where r.restaurantId = :restaurantId
              and r.active = true
              and (:cursorRating is null
                   or r.rating.value > :cursorRating
                   or (r.rating.value = :cursorRating and r.id < :cursorId))
            order by r.rating.value asc, r.id desc
            """)
    List<Review> findRatingLowPage(
            @Param("restaurantId") Long restaurantId,
            @Param("cursorRating") Integer cursorRating,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    interface RatingCount {

        Integer getRating();

        Long getCount();
    }
}
