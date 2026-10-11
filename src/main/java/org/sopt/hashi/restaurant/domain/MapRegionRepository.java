package org.sopt.hashi.restaurant.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MapRegionRepository extends JpaRepository<MapRegion, Long> {

    List<MapRegion> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();
}
