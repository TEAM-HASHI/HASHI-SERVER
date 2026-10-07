package org.sopt.hashi.restaurant.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MapRegionRepository extends JpaRepository<MapRegion, Long> {

    List<MapRegion> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();

    Optional<MapRegion> findByCode(String code);

    /** unique code가 생성 경쟁과 전체 설정 교체를 직렬화하며 기존 id/created_at은 유지한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO map_region (code, name, cluster_latitude, cluster_longitude,
                south, north, west, east, display_order, active, created_at, updated_at)
            VALUES (:#{#region.code}, :#{#region.name}, :#{#region.clusterPosition.latitude},
                :#{#region.clusterPosition.longitude}, :#{#region.cameraBounds.south},
                :#{#region.cameraBounds.north}, :#{#region.cameraBounds.west}, :#{#region.cameraBounds.east},
                :#{#region.displayOrder}, :#{#region.active}, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE name=:#{#region.name},
                cluster_latitude=:#{#region.clusterPosition.latitude},
                cluster_longitude=:#{#region.clusterPosition.longitude},
                south=:#{#region.cameraBounds.south}, north=:#{#region.cameraBounds.north},
                west=:#{#region.cameraBounds.west}, east=:#{#region.cameraBounds.east},
                display_order=:#{#region.displayOrder}, active=:#{#region.active}, updated_at=CURRENT_TIMESTAMP(6)
            """, nativeQuery = true)
    void upsert(@Param("region") MapRegion region);
}
