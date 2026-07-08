package org.sopt.hashi.point.domain;

import java.util.Optional;
import org.sopt.hashi.point.PointSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    Optional<PointTransaction> findByTypeAndSourceTypeAndSourceId(
            PointTransactionType type, PointSourceType sourceType, Long sourceId);

    boolean existsByTypeAndSourceTypeAndSourceId(
            PointTransactionType type, PointSourceType sourceType, Long sourceId);
}
