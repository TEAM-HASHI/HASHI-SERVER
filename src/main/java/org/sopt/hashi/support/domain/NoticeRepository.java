package org.sopt.hashi.support.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, Long> {
    Optional<Notice> findByIdAndDeletedFalseAndPublishedAtIsNotNull(Long id);
    Optional<Notice> findByIdAndDeletedFalse(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notice n where n.id = :id and n.deleted = false")
    Optional<Notice> findForUpdate(@Param("id") Long id);

    @Query("""
            select n from Notice n where n.deleted = false and n.publishedAt is not null
            and (:before is null or n.publishedAt < :before
                or (n.publishedAt = :before and n.id < :id))
            order by n.publishedAt desc, n.id desc
            """)
    List<Notice> findPublished(@Param("before") LocalDateTime before, @Param("id") Long id, Limit limit);

    @Query("""
            select n from Notice n where n.deleted = false and (:id is null or n.id < :id)
            order by n.id desc
            """)
    List<Notice> findAdminPage(@Param("id") Long id, Limit limit);
}
