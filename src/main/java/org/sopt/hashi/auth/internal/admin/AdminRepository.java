package org.sopt.hashi.auth.internal.admin;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByLoginId(String loginId);
}
