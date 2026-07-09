package org.sopt.hashi.magazine.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MagazineRepository extends JpaRepository<Magazine, Long> {

    List<Magazine> findAllByOrderByIdDesc(Pageable pageable);

    List<Magazine> findByIdLessThanOrderByIdDesc(Long id, Pageable pageable);
}
