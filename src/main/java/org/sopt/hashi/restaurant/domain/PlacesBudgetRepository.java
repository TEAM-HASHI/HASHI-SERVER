package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.sopt.hashi.restaurant.domain.PlacesBudget.Operation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlacesBudgetRepository extends JpaRepository<PlacesBudget, Operation> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PlacesBudget b where b.operation = :operation")
    Optional<PlacesBudget> findByOperationForUpdate(@Param("operation") Operation operation);
}
