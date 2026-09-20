package org.sopt.hashi.media.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageRenditionRepository extends JpaRepository<ImageRendition, Long> {

    @Query("""
            select asset.publicId as assetId,
                   rendition.role as role,
                   rendition.mimeType as mimeType,
                   rendition.width as width,
                   rendition.height as height,
                   rendition.objectKey as objectKey
            from ImageRendition rendition
            join rendition.imageAsset asset
            where asset.publicId in :assetIds
              and rendition.role in :roles
              and rendition.specVersion = asset.activeSpecVersion
            """)
    List<RenditionImageProjection> findActiveImageProjections(
            @Param("assetIds") Collection<UUID> assetIds,
            @Param("roles") Collection<ImageRole> roles
    );

    interface RenditionImageProjection {

        UUID getAssetId();

        ImageRole getRole();

        String getMimeType();

        int getWidth();

        int getHeight();

        String getObjectKey();
    }
}
