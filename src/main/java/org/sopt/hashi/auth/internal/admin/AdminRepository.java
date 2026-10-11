package org.sopt.hashi.auth.internal.admin;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByLoginId(String loginId);

    @Query("select a.id as id, a.loginId as loginId from Admin a where a.id in :adminIds")
    List<AdminLoginIdProjection> findLoginIds(@Param("adminIds") Set<Long> adminIds);

    interface AdminLoginIdProjection {
        Long getId();

        String getLoginId();
    }
}
