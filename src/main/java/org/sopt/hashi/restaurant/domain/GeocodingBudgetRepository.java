package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GeocodingBudgetRepository extends JpaRepository<GeocodingBudget, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from GeocodingBudget b where b.id = 1")
    Optional<GeocodingBudget> findControlForUpdate();

    // A string avoids the driver's Timestamp/session-zone conversion at the clock boundary.
    @Query(value = "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%f')", nativeQuery = true)
    String databaseUtcTime();
}
