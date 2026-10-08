package org.sopt.hashi.support.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.sopt.hashi.support.TermsType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsTypeStateRepository extends JpaRepository<TermsTypeState, TermsType> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TermsTypeState s where s.type = :type")
    Optional<TermsTypeState> findForUpdate(@Param("type") TermsType type);
}
