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

public interface RestaurantLocationJobRepository extends JpaRepository<RestaurantLocationJob, Long> {

    Optional<RestaurantLocationJob> findByRestaurantIdAndRequestId(Long restaurantId, UUID requestId);

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
            where (j.state = 'PENDING' and (j.reservedUntil is null or j.reservedUntil <= :now))
               or (j.state = 'RETRY_WAIT' and j.nextAttemptAt <= :now
                    and (j.reservedUntil is null or j.reservedUntil <= :now))
               or (j.state = 'LEASED' and j.leaseUntil <= :now)
            order by j.nextAttemptAt, j.id
            """)
    List<RestaurantLocationJob> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("select count(j) from RestaurantLocationJob j where j.reservedUntil > :now")
    long countReservations(@Param("now") LocalDateTime now);
}
