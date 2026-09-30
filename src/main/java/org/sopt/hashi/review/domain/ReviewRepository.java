package org.sopt.hashi.review.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationId(Long reservationId);

    Optional<Review> findByReservationId(Long reservationId);

    List<Review> findByReservationIdIn(Collection<Long> reservationIds);

    Optional<Review> findByIdAndUserIdAndDeletedFalse(Long reviewId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
            from Review r
            where r.id = :reviewId
              and r.userId = :userId
              and r.deleted = false
            """)
    Optional<Review> findOwnedActiveForUpdate(
            @Param("reviewId") Long reviewId,
            @Param("userId") Long userId
    );

    long countByUserIdAndDeletedFalse(Long userId);

    List<Review> findByUserIdAndDeletedFalseOrderByIdDesc(Long userId, Pageable pageable);

    List<Review> findByUserIdAndDeletedFalseAndIdLessThanOrderByIdDesc(
            Long userId, Long cursor, Pageable pageable);

    Optional<Review> findByIdAndRestaurantIdAndDeletedFalse(Long id, Long restaurantId);

    long countByRestaurantIdAndDeletedFalse(Long restaurantId);

    @Query("""
            select avg(r.rating.value)
            from Review r
            where r.restaurantId = :restaurantId
              and r.deleted = false
            """)
    Double averageRatingByRestaurantId(@Param("restaurantId") Long restaurantId);

    @Query("""
            select r.rating.value as rating, count(r) as count
            from Review r
            where r.restaurantId = :restaurantId
              and r.deleted = false
            group by r.rating.value
            """)
    List<RatingCount> countByRating(@Param("restaurantId") Long restaurantId);

    @Query("""
            select r
            from Review r
            where r.restaurantId = :restaurantId
              and r.deleted = false
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
              and r.deleted = false
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
              and r.deleted = false
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
