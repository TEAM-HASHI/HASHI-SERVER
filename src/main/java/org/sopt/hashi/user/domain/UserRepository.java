package org.sopt.hashi.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
