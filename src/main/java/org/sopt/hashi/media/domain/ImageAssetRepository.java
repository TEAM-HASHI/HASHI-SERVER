package org.sopt.hashi.media.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {

    Optional<ImageAsset> findByPublicId(UUID publicId);

    Optional<ImageAsset> findByBackfillIdentityHash(String backfillIdentityHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from ImageAsset asset where asset.publicId = :publicId")
    Optional<ImageAsset> findByPublicIdForUpdate(@Param("publicId") UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from ImageAsset asset where asset.id = :id")
    Optional<ImageAsset> findByIdForUpdate(@Param("id") Long id);

    List<ImageAsset> findAllByPublicIdIn(Collection<UUID> publicIds);

    @Query("""
            select asset.id as id, asset.publicId as publicId
            from ImageAsset asset
            where asset.publicId in :publicIds
            """)
    List<AssetIdentity> findIdentitiesByPublicIdIn(
            @Param("publicIds") Collection<UUID> publicIds
    );

    @Query("""
            select asset.publicId as publicId,
                   asset.purpose as purpose,
                   asset.processingStatus as processingStatus,
                   asset.bindingStatus as bindingStatus,
                   asset.cleanupStatus as cleanupStatus,
                   asset.activeSpecVersion as activeSpecVersion,
                   asset.activeSpecDigest as activeSpecDigest,
                   asset.targetSpecVersion as targetSpecVersion,
                   asset.targetSpecDigest as targetSpecDigest,
                   asset.lastFailureSpecVersion as lastFailureSpecVersion
            from ImageAsset asset
            where asset.publicId in :publicIds
            """)
    List<AssetImageProjection> findImageProjectionsByPublicIdIn(
            @Param("publicIds") Collection<UUID> publicIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select asset
            from ImageAsset asset
            where asset.publicId in :publicIds
            order by asset.id asc
            """)
    List<ImageAsset> findAllByPublicIdInForUpdate(@Param("publicIds") Collection<UUID> publicIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select asset
            from ImageAsset asset
            where asset.id in :ids
            order by asset.id asc
            """)
    List<ImageAsset> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);

    @Query("""
            select asset.id as assetId,
                   asset.currentJobId as jobId,
                   asset.targetProcessingStartedAt as startedAt
            from ImageAsset asset
            where asset.cleanupStatus = :cleanupStatus
              and asset.targetProcessingStatus = :targetStatus
              and asset.targetProcessingStartedAt <= :staleBefore
              and (
                    asset.lastRecoveryRequestedAt is null
                    or asset.lastRecoveryRequestedAt <= :retryBefore
              )
              and asset.processingRecoveryAttempts < :maxAttempts
              and (
                    asset.targetProcessingStartedAt > :cursorStartedAt
                    or (
                        asset.targetProcessingStartedAt = :cursorStartedAt
                        and asset.id > :cursorId
                    )
              )
            order by asset.targetProcessingStartedAt asc, asset.id asc
            """)
    List<ProcessingRecoveryCandidate> findProcessingRecoveryCandidates(
            @Param("cleanupStatus") MediaCleanupStatus cleanupStatus,
            @Param("targetStatus") TargetProcessingStatus targetStatus,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("retryBefore") LocalDateTime retryBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("cursorStartedAt") LocalDateTime cursorStartedAt,
            @Param("cursorId") Long cursorId,
            Limit limit
    );

    @Query("""
            select asset.id as assetId,
                   asset.publicId as publicId,
                   asset.processingStatus as processingStatus,
                   asset.creationOrigin as creationOrigin,
                   asset.updatedAt as updatedAt
            from ImageAsset asset
            where asset.cleanupStatus = :cleanupStatus
              and asset.bindingStatus = :bindingStatus
              and asset.processingStatus in :processingStatuses
              and asset.creationOrigin = :creationOrigin
              and asset.targetProcessingStatus is null
              and asset.updatedAt <= :updatedBefore
              and (
                    asset.updatedAt > :cursorUpdatedAt
                    or (
                        asset.updatedAt = :cursorUpdatedAt
                        and asset.id > :cursorId
                    )
              )
            order by asset.updatedAt asc, asset.id asc
            """)
    List<CleanupCandidate> findCleanupCandidates(
            @Param("cleanupStatus") MediaCleanupStatus cleanupStatus,
            @Param("bindingStatus") ImageBindingStatus bindingStatus,
            @Param("processingStatuses") Collection<ImageProcessingStatus> processingStatuses,
            @Param("creationOrigin") MediaCreationOrigin creationOrigin,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("cursorUpdatedAt") LocalDateTime cursorUpdatedAt,
            @Param("cursorId") Long cursorId,
            Limit limit
    );

    @Query("""
            select asset.processingStatus as status, count(asset) as assetCount
            from ImageAsset asset
            group by asset.processingStatus
            """)
    List<ProcessingStatusCount> countByProcessingStatus();

    @Query("""
            select count(asset)
            from ImageAsset asset
            where asset.cleanupStatus = :cleanupStatus
              and asset.targetProcessingStatus = :targetStatus
              and asset.targetProcessingStartedAt <= :staleBefore
            """)
    long countStalledProcessing(
            @Param("cleanupStatus") MediaCleanupStatus cleanupStatus,
            @Param("targetStatus") TargetProcessingStatus targetStatus,
            @Param("staleBefore") LocalDateTime staleBefore
    );

    @Query("""
            select count(asset)
            from ImageAsset asset
            where asset.cleanupStatus = :cleanupStatus
              and asset.targetProcessingStatus = :targetStatus
              and asset.processingRecoveryAttempts >= :maxAttempts
            """)
    long countExhaustedProcessingRecovery(
            @Param("cleanupStatus") MediaCleanupStatus cleanupStatus,
            @Param("targetStatus") TargetProcessingStatus targetStatus,
            @Param("maxAttempts") int maxAttempts
    );

    @Query("""
            select min(asset.targetProcessingStartedAt)
            from ImageAsset asset
            where asset.cleanupStatus = :cleanupStatus
              and asset.targetProcessingStatus = :targetStatus
            """)
    Optional<LocalDateTime> findOldestProcessingStartedAt(
            @Param("cleanupStatus") MediaCleanupStatus cleanupStatus,
            @Param("targetStatus") TargetProcessingStatus targetStatus
    );

    long countByCleanupStatusAndProcessingStatusInAndUpdatedAtBefore(
            MediaCleanupStatus cleanupStatus,
            Collection<ImageProcessingStatus> statuses,
            LocalDateTime updatedAt
    );

    long countByCleanupStatusAndBindingStatusAndCreationOriginAndProcessingStatusAndUpdatedAtBefore(
            MediaCleanupStatus cleanupStatus,
            ImageBindingStatus bindingStatus,
            MediaCreationOrigin creationOrigin,
            ImageProcessingStatus processingStatus,
            LocalDateTime updatedAt
    );

    long countByCleanupStatusAndProcessingStatusAndUpdatedAtBefore(
            MediaCleanupStatus cleanupStatus,
            ImageProcessingStatus processingStatus,
            LocalDateTime updatedAt
    );

    interface ProcessingRecoveryCandidate {

        Long getAssetId();

        UUID getJobId();

        LocalDateTime getStartedAt();
    }

    interface AssetIdentity {

        Long getId();

        UUID getPublicId();
    }

    interface AssetImageProjection {

        UUID getPublicId();

        MediaPurpose getPurpose();

        ImageProcessingStatus getProcessingStatus();

        ImageBindingStatus getBindingStatus();

        MediaCleanupStatus getCleanupStatus();

        Integer getActiveSpecVersion();

        String getActiveSpecDigest();

        Integer getTargetSpecVersion();

        String getTargetSpecDigest();

        Integer getLastFailureSpecVersion();
    }

    interface ProcessingStatusCount {

        ImageProcessingStatus getStatus();

        long getAssetCount();
    }

    interface CleanupCandidate {

        Long getAssetId();

        UUID getPublicId();

        ImageProcessingStatus getProcessingStatus();

        MediaCreationOrigin getCreationOrigin();

        LocalDateTime getUpdatedAt();
    }
}
