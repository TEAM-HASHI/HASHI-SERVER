package org.sopt.hashi.support.domain;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import org.sopt.hashi.support.TermsType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsVersionRepository extends JpaRepository<TermsVersion, Long> {
    @Query("select v.type from TermsVersion v where v.id = :id")
    Optional<TermsType> findType(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from TermsVersion v where v.id = :id")
    Optional<TermsVersion> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from TermsVersion v where v.type = :type and v.version = :version")
    Optional<TermsVersion> findVersionForUpdate(@Param("type") TermsType type, @Param("version") String version);

    @Query("""
            select v from TermsVersion v join TermsTypeState s
            on s.currentVersionId = v.id and s.type = v.type where v.publishedAt is not null
            """)
    List<TermsVersion> findCurrent();

    @Query("""
            select v from TermsVersion v where v.type = :type
            and (:id is null or v.id < :id) order by v.id desc
            """)
    List<TermsVersion> findHistory(@Param("type") TermsType type, @Param("id") Long id, Limit limit);
}
