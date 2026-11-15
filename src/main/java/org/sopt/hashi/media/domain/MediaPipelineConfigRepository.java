package org.sopt.hashi.media.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaPipelineConfigRepository extends JpaRepository<MediaPipelineConfig, Integer> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select config from MediaPipelineConfig config where config.id = :id")
    Optional<MediaPipelineConfig> findByIdForShare(@Param("id") Integer id);
}
