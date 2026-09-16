package org.sopt.hashi.media.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {

    Optional<ImageAsset> findByPublicId(UUID publicId);

    List<ImageAsset> findAllByPublicIdIn(Collection<UUID> publicIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select asset
            from ImageAsset asset
            where asset.publicId in :publicIds
            order by asset.id asc
            """)
    List<ImageAsset> findAllByPublicIdInForUpdate(@Param("publicIds") Collection<UUID> publicIds);
}
