package org.sopt.hashi.media.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(name = "image_asset")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageAsset extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 36, nullable = false, updatable = false, unique = true)
    private UUID publicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 40, nullable = false, updatable = false)
    private MediaPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "creation_origin", length = 30, nullable = false, updatable = false)
    private MediaCreationOrigin creationOrigin;

    @Enumerated(EnumType.STRING)
    @Column(name = "creator_actor_type", length = 30, updatable = false)
    private MediaOwnerType creatorActorType;

    @Column(name = "creator_subject_id", updatable = false)
    private Long creatorSubjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_actor_type", length = 30, nullable = false)
    private MediaOwnerType ownerActorType;

    @Column(name = "owner_subject_id")
    private Long ownerSubjectId;

    @Column(name = "original_object_key", length = 500, nullable = false, updatable = false, unique = true)
    private String originalObjectKey;

    @Column(name = "declared_content_type", length = 50, nullable = false, updatable = false)
    private String declaredContentType;

    @Column(name = "declared_bytes", nullable = false, updatable = false)
    private long declaredBytes;

    @Column(name = "upload_expires_at", nullable = false, updatable = false)
    private LocalDateTime uploadExpiresAt;

    @Column(name = "source_version_id", length = 1024)
    private String sourceVersionId;

    @Column(name = "source_etag", length = 255)
    private String sourceEtag;

    @Column(name = "actual_content_type", length = 50)
    private String actualContentType;

    @Column(name = "actual_bytes")
    private Long actualBytes;

    @Column(name = "source_width")
    private Integer sourceWidth;

    @Column(name = "source_height")
    private Integer sourceHeight;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_checksum_sha256", length = 44)
    private String sourceChecksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 30, nullable = false)
    private ImageProcessingStatus processingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "binding_status", length = 20, nullable = false)
    private ImageBindingStatus bindingStatus;

    @Column(name = "active_spec_version")
    private Integer activeSpecVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_spec_digest", length = 64)
    private String activeSpecDigest;

    @Column(name = "target_spec_version")
    private Integer targetSpecVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "target_spec_digest", length = 64)
    private String targetSpecDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_processing_status", length = 20)
    private TargetProcessingStatus targetProcessingStatus;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "current_job_id", length = 36)
    private UUID currentJobId;

    @Column(name = "last_issued_spec_version")
    private Integer lastIssuedSpecVersion;

    @Column(name = "last_failure_spec_version")
    private Integer lastFailureSpecVersion;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "backfill_identity_hash", length = 64, unique = true)
    private String backfillIdentityHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "cleanup_status", length = 20, nullable = false)
    private MediaCleanupStatus cleanupStatus;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "purge_token", length = 36)
    private UUID purgeToken;

    @Column(name = "purge_started_at")
    private LocalDateTime purgeStartedAt;

    @Column(name = "objects_purged_at")
    private LocalDateTime objectsPurgedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OrderBy("role ASC, width ASC")
    @OneToMany(mappedBy = "imageAsset", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ImageRendition> renditions = new ArrayList<>();

    private ImageAsset(UUID publicId, MediaPurpose purpose, MediaCreationOrigin creationOrigin,
                       MediaOwnerType creatorActorType, Long creatorSubjectId,
                       MediaOwnerType ownerActorType, Long ownerSubjectId,
                       String originalObjectKey, String declaredContentType, long declaredBytes,
                       LocalDateTime uploadExpiresAt, String backfillIdentityHash) {
        this.publicId = Objects.requireNonNull(publicId);
        this.purpose = Objects.requireNonNull(purpose);
        this.creationOrigin = Objects.requireNonNull(creationOrigin);
        this.creatorActorType = creatorActorType;
        this.creatorSubjectId = creatorSubjectId;
        this.ownerActorType = Objects.requireNonNull(ownerActorType);
        this.ownerSubjectId = ownerSubjectId;
        this.originalObjectKey = requireText(originalObjectKey, "originalObjectKey");
        this.declaredContentType = requireText(declaredContentType, "declaredContentType");
        if (declaredBytes <= 0) {
            throw new IllegalArgumentException("declaredBytes must be positive");
        }
        this.declaredBytes = declaredBytes;
        this.uploadExpiresAt = Objects.requireNonNull(uploadExpiresAt);
        this.backfillIdentityHash = backfillIdentityHash;
        this.processingStatus = ImageProcessingStatus.PENDING_UPLOAD;
        this.bindingStatus = ImageBindingStatus.UNBOUND;
        this.cleanupStatus = MediaCleanupStatus.ACTIVE;
    }

    public static ImageAsset createDirectUpload(UUID publicId, MediaPurpose purpose,
                                                 MediaOwnerType actorType, Long actorSubjectId,
                                                 String originalObjectKey, String declaredContentType,
                                                 long declaredBytes, LocalDateTime uploadExpiresAt) {
        if (actorType == MediaOwnerType.SYSTEM_BACKFILL || actorSubjectId == null) {
            throw new IllegalArgumentException("direct upload requires an authenticated actor");
        }
        return new ImageAsset(
                publicId,
                purpose,
                MediaCreationOrigin.DIRECT_UPLOAD,
                actorType,
                actorSubjectId,
                actorType,
                actorSubjectId,
                originalObjectKey,
                declaredContentType,
                declaredBytes,
                uploadExpiresAt,
                null
        );
    }

    public static ImageAsset createSystemBackfill(UUID publicId, MediaPurpose purpose,
                                                   String originalObjectKey, String declaredContentType,
                                                   long declaredBytes, LocalDateTime uploadExpiresAt,
                                                   String backfillIdentityHash) {
        return new ImageAsset(
                publicId,
                purpose,
                MediaCreationOrigin.SYSTEM_BACKFILL,
                null,
                null,
                MediaOwnerType.SYSTEM_BACKFILL,
                null,
                originalObjectKey,
                declaredContentType,
                declaredBytes,
                uploadExpiresAt,
                requireText(backfillIdentityHash, "backfillIdentityHash")
        );
    }

    public List<ImageRendition> getRenditions() {
        return Collections.unmodifiableList(renditions);
    }

    public boolean isOwnedBy(MediaOwnerType actorType, Long actorSubjectId) {
        return ownerActorType == actorType && Objects.equals(ownerSubjectId, actorSubjectId);
    }

    public boolean hasCurrentProcessingJob(UUID jobId) {
        return cleanupStatus == MediaCleanupStatus.ACTIVE
                && targetProcessingStatus == TargetProcessingStatus.PROCESSING
                && Objects.equals(currentJobId, jobId);
    }

    public boolean matchesCurrentProcessingAttempt(UUID jobId, String sourceVersionId,
                                                   String sourceEtag) {
        return hasCurrentProcessingJob(jobId)
                && Objects.equals(this.sourceVersionId, sourceVersionId)
                && Objects.equals(this.sourceEtag, sourceEtag);
    }

    public boolean matchesTargetSpec(int specVersion, String specDigest) {
        return Objects.equals(targetSpecVersion, specVersion)
                && Objects.equals(targetSpecDigest, specDigest);
    }

    public boolean isUploadExpired(LocalDateTime now) {
        return !now.isBefore(uploadExpiresAt);
    }

    public void expireUpload() {
        requireState(ImageProcessingStatus.PENDING_UPLOAD);
        processingStatus = ImageProcessingStatus.EXPIRED;
    }

    public void beginInitialProcessing(String sourceVersionId, String sourceEtag,
                                       int specVersion, String specDigest, UUID jobId) {
        requireState(ImageProcessingStatus.PENDING_UPLOAD);
        this.sourceVersionId = requireText(sourceVersionId, "sourceVersionId");
        this.sourceEtag = requireText(sourceEtag, "sourceEtag");
        beginTargetProcessing(specVersion, specDigest, jobId);
        this.processingStatus = ImageProcessingStatus.PROCESSING;
    }

    public void beginUpgradeProcessing(int specVersion, String specDigest, UUID jobId) {
        if (cleanupStatus != MediaCleanupStatus.ACTIVE) {
            throw new IllegalStateException("only active assets can be upgraded");
        }
        requireState(ImageProcessingStatus.READY);
        if (activeSpecVersion == null || actualContentType == null) {
            throw new IllegalStateException("only a verified active spec can be upgraded");
        }
        beginTargetProcessing(specVersion, specDigest, jobId);
    }

    public void addRendition(UUID jobId, int specVersion, String specDigest,
                             ImageRole role, ImageFormat format, int width, int height,
                             long bytes, String objectKey) {
        requireCurrentTarget(jobId, specVersion, specDigest);
        boolean duplicate = renditions.stream().anyMatch(rendition ->
                rendition.getRole() == role
                        && rendition.getSpecVersion() == specVersion
                        && rendition.getFormat() == format
                        && rendition.getWidth() == width);
        if (duplicate) {
            throw new IllegalStateException("rendition identity is duplicated");
        }
        renditions.add(ImageRendition.create(
                this, role, specVersion, format, width, height, bytes, objectKey));
    }

    public void completeCurrentProcessing(UUID jobId, int specVersion, String specDigest,
                                          String actualContentType, long actualBytes,
                                          int sourceWidth, int sourceHeight,
                                          String sourceChecksumSha256) {
        requireCurrentTarget(jobId, specVersion, specDigest);
        requireVerifiedSource(
                actualContentType, actualBytes, sourceWidth, sourceHeight, sourceChecksumSha256);
        this.activeSpecVersion = specVersion;
        this.activeSpecDigest = specDigest;
        this.processingStatus = ImageProcessingStatus.READY;
        clearTargetProcessing();
    }

    public void failCurrentProcessing(UUID jobId, int specVersion, String specDigest,
                                      String failureCode) {
        requireCurrentTarget(jobId, specVersion, specDigest);
        this.lastFailureSpecVersion = specVersion;
        this.lastFailureCode = requireText(failureCode, "failureCode");
        if (activeSpecVersion == null) {
            this.processingStatus = ImageProcessingStatus.FAILED;
        } else {
            this.processingStatus = ImageProcessingStatus.READY;
        }
        clearTargetProcessing();
    }

    private void beginTargetProcessing(int specVersion, String specDigest, UUID jobId) {
        if (targetProcessingStatus != null) {
            throw new IllegalStateException("another media processing target is active");
        }
        if (specVersion < 1
                || (lastIssuedSpecVersion != null && specVersion <= lastIssuedSpecVersion)) {
            throw new IllegalArgumentException(
                    "specVersion must be greater than the last issued version");
        }
        this.targetSpecVersion = specVersion;
        this.targetSpecDigest = requireSha256(specDigest, "specDigest");
        this.targetProcessingStatus = TargetProcessingStatus.PROCESSING;
        this.currentJobId = Objects.requireNonNull(jobId);
        this.lastIssuedSpecVersion = specVersion;
    }

    private void requireCurrentTarget(UUID jobId, int specVersion, String specDigest) {
        if (!hasCurrentProcessingJob(jobId) || !matchesTargetSpec(specVersion, specDigest)) {
            throw new IllegalStateException("media processing result is not current");
        }
    }

    private void requireVerifiedSource(String contentType, long bytes, int width, int height,
                                       String checksumSha256) {
        String verifiedContentType = requireText(contentType, "actualContentType");
        String verifiedChecksum = requireBase64Sha256(checksumSha256, "sourceChecksumSha256");
        if (bytes < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("verified source values must be positive");
        }
        if (!declaredContentType.equalsIgnoreCase(verifiedContentType)
                || declaredBytes != bytes) {
            throw new IllegalStateException("verified source differs from the upload declaration");
        }
        if (actualContentType != null) {
            boolean matchesExisting = actualContentType.equals(verifiedContentType)
                    && Objects.equals(actualBytes, bytes)
                    && Objects.equals(sourceWidth, width)
                    && Objects.equals(sourceHeight, height)
                    && Objects.equals(sourceChecksumSha256, verifiedChecksum);
            if (!matchesExisting) {
                throw new IllegalStateException("verified source identity has changed");
            }
            return;
        }
        this.actualContentType = verifiedContentType;
        this.actualBytes = bytes;
        this.sourceWidth = width;
        this.sourceHeight = height;
        this.sourceChecksumSha256 = verifiedChecksum;
    }

    private void clearTargetProcessing() {
        this.targetSpecVersion = null;
        this.targetSpecDigest = null;
        this.targetProcessingStatus = null;
        this.currentJobId = null;
    }

    private void requireState(ImageProcessingStatus expectedStatus) {
        if (processingStatus != expectedStatus) {
            throw new IllegalStateException(
                    "expected %s but was %s".formatted(expectedStatus, processingStatus));
        }
    }

    private static String requireSha256(String value, String fieldName) {
        String text = requireText(value, fieldName);
        if (!text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 digest");
        }
        return text;
    }

    private static String requireBase64Sha256(String value, String fieldName) {
        String text = requireText(value, fieldName);
        if (!text.matches("[A-Za-z0-9+/]{43}=")) {
            throw new IllegalArgumentException(fieldName + " must be a Base64 SHA-256 digest");
        }
        return text;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
