package org.sopt.hashi.magazine.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazineRepository extends JpaRepository<Magazine, Long> {

    List<Magazine> findAllByOrderByIdDesc(Pageable pageable);

    List<Magazine> findByIdLessThanOrderByIdDesc(Long id, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select magazine from Magazine magazine where magazine.id = :id")
    Optional<Magazine> findByIdForUpdate(@Param("id") Long id);
}
