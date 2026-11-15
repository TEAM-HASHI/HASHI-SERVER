package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob.Operation;

public interface RestaurantLocationJobRepository extends JpaRepository<RestaurantLocationJob, Long> {

    Optional<RestaurantLocationJob> findByRestaurantIdAndRequestId(Long restaurantId, UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from RestaurantLocationJob j where j.restaurantId = :restaurantId and j.requestId = :requestId")
    Optional<RestaurantLocationJob> findCurrentForUpdate(@Param("restaurantId") Long restaurantId,
                                                         @Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from RestaurantLocationJob j where j.id = :id")
    Optional<RestaurantLocationJob> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from RestaurantLocationJob j where j.restaurantId = :restaurantId
            and j.state in ('PENDING', 'LEASED', 'RETRY_WAIT') order by j.id
            """)
    List<RestaurantLocationJob> findActiveForUpdate(@Param("restaurantId") Long restaurantId);

    @Query("""
            select j from RestaurantLocationJob j
            where j.operation = :operation and (
               (j.state = 'PENDING' and (j.reservedUntil is null or j.reservedUntil <= :now))
               or (j.state = 'RETRY_WAIT' and j.nextAttemptAt <= :now
                    and (j.reservedUntil is null or j.reservedUntil <= :now))
               or (j.state = 'LEASED' and j.leaseUntil <= :now))
            order by j.nextAttemptAt, j.id
            """)
    List<RestaurantLocationJob> findDue(@Param("now") LocalDateTime now,
                                        @Param("operation") Operation operation, Pageable pageable);

    /** Budget pauses must not hide cleanup behind ordinary jobs that cannot make a call. */
    @Query("""
            select j from RestaurantLocationJob j
            where j.operation = :operation and j.attempt >= :maxAttempts and (
                (j.state = 'PENDING' and (j.reservedUntil is null or j.reservedUntil <= :now))
                or (j.state = 'RETRY_WAIT' and j.nextAttemptAt <= :now
                    and (j.reservedUntil is null or j.reservedUntil <= :now))
                or (j.state = 'LEASED' and j.leaseUntil <= :now))
            order by j.nextAttemptAt, j.id
            """)
    List<RestaurantLocationJob> findDueExhausted(@Param("now") LocalDateTime now,
                                               @Param("maxAttempts") int maxAttempts,
                                               @Param("operation") Operation operation, Pageable pageable);

    @Query("select count(j) from RestaurantLocationJob j where j.operation = :operation and j.reservedUntil > :now")
    long countReservations(@Param("now") LocalDateTime now, @Param("operation") Operation operation);
}
