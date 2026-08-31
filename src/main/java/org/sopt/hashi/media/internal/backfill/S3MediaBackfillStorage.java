package org.sopt.hashi.media.internal.backfill;

import static org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException.Reason.COPY_CONFLICT;
import static org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException.Reason.INVALID_SOURCE;
import static org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException.Reason.SOURCE_CHANGED;
import static org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException.Reason.SOURCE_MISSING;
import static org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException.Reason.SOURCE_UNREADABLE;
import static org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException.Reason.STORAGE_UNAVAILABLE;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.TaggingDirective;

public class S3MediaBackfillStorage implements MediaBackfillStorage {

    static final String IDENTITY_METADATA_KEY = "backfill-identity";
    private static final int VERSION_PAGE_SIZE = 100;
    private static final int MAX_VERSION_PAGES = 100;

    private final S3Client s3Client;
    private final String deliveryBucket;
    private final String originalBucket;

    public S3MediaBackfillStorage(S3Client s3Client, String deliveryBucket, String originalBucket) {
        if (deliveryBucket == null || deliveryBucket.isBlank()
                || originalBucket == null || originalBucket.isBlank()
                || deliveryBucket.equals(originalBucket)) {
            throw new IllegalArgumentException("backfill requires separate delivery and original buckets");
        }
        this.s3Client = Objects.requireNonNull(s3Client);
        this.deliveryBucket = deliveryBucket;
        this.originalBucket = originalBucket;
    }

    @Override
    public LegacyImageSource inspectSource(String legacyKey) {
        assertOutsideTransaction();
        if (legacyKey == null || legacyKey.isBlank() || legacyKey.startsWith("media/")) {
            throw new MediaBackfillStorageException(INVALID_SOURCE);
        }
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(deliveryBucket).key(legacyKey).build());
            return new LegacyImageSource(deliveryBucket, legacyKey, head.versionId(), head.eTag(),
                    head.contentType(), head.contentLength() == null ? 0 : head.contentLength());
        } catch (S3Exception e) {
            throw inspectSourceFailure(e);
        } catch (SdkException e) {
            throw new MediaBackfillStorageException(STORAGE_UNAVAILABLE);
        } catch (IllegalArgumentException e) {
            throw new MediaBackfillStorageException(INVALID_SOURCE);
        }
    }

    @Override
    public BackfillOriginalCopy findOrCopyOriginal(UUID assetId, String identityHash, LegacyImageSource source) {
        assertOutsideTransaction();
        Objects.requireNonNull(assetId, "assetId is required");
        Objects.requireNonNull(source, "source is required");
        if (identityHash == null || !identityHash.matches("[0-9a-f]{64}")
                || !deliveryBucket.equals(source.bucket())) {
            throw new MediaBackfillStorageException(INVALID_SOURCE);
        }
        String objectKey = "media/originals/%s/original".formatted(assetId);
        try {
            Optional<BackfillOriginalCopy> existing = findExistingCopy(objectKey, identityHash, source);
            if (existing.isPresent()) {
                return existing.get();
            }
            CopyObjectResponse copy = copySource(objectKey, identityHash, source);
            if (copy.versionId() == null || copy.versionId().isBlank() || "null".equals(copy.versionId())) {
                throw new MediaBackfillStorageException(COPY_CONFLICT);
            }
            // 응답 유실과 동시 copy가 있어도 DB에는 이 exact version만 고정한다.
            return inspectCopy(objectKey, copy.versionId(), identityHash, source)
                    .orElseThrow(() -> new MediaBackfillStorageException(COPY_CONFLICT));
        } catch (S3Exception e) {
            throw new MediaBackfillStorageException(STORAGE_UNAVAILABLE);
        } catch (SdkException e) {
            throw new MediaBackfillStorageException(STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public void close() {
        s3Client.close();
    }

    private Optional<BackfillOriginalCopy> findExistingCopy(
            String objectKey, String identityHash, LegacyImageSource source) {
        String keyMarker = null;
        String versionMarker = null;
        Set<VersionMarker> seenMarkers = new HashSet<>();
        for (int page = 0; page < MAX_VERSION_PAGES; page++) {
            ListObjectVersionsResponse versions = s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(originalBucket).prefix(objectKey).maxKeys(VERSION_PAGE_SIZE)
                    .keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            for (ObjectVersion version : versions.versions()) {
                if (!objectKey.equals(version.key())) {
                    continue;
                }
                Optional<BackfillOriginalCopy> found = inspectCopy(
                        objectKey, version.versionId(), identityHash, source);
                if (found.isPresent()) {
                    return found;
                }
            }
            if (!Boolean.TRUE.equals(versions.isTruncated())) {
                return Optional.empty();
            }
            keyMarker = versions.nextKeyMarker();
            versionMarker = versions.nextVersionIdMarker();
            boolean validMarker = keyMarker != null && !keyMarker.isBlank()
                    && seenMarkers.add(new VersionMarker(keyMarker, versionMarker));
            if (!validMarker) {
                throw new MediaBackfillStorageException(STORAGE_UNAVAILABLE);
            }
        }
        // 재사용 여부를 끝까지 확인하지 못한 경우 새 copy를 만들지 않는다.
        throw new MediaBackfillStorageException(STORAGE_UNAVAILABLE);
    }

    private Optional<BackfillOriginalCopy> inspectCopy(
            String objectKey, String versionId, String identityHash, LegacyImageSource source) {
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new MediaBackfillStorageException(COPY_CONFLICT);
        }
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(originalBucket).key(objectKey).versionId(versionId).build());
            boolean matches = identityHash.equals(head.metadata().get(IDENTITY_METADATA_KEY))
                    && source.contentType().equalsIgnoreCase(head.contentType())
                    && Objects.equals(source.bytes(), head.contentLength())
                    && versionId.equals(head.versionId())
                    && head.eTag() != null && !head.eTag().isBlank();
            if (!matches) {
                throw new MediaBackfillStorageException(COPY_CONFLICT);
            }
            return Optional.of(new BackfillOriginalCopy(objectKey, versionId, head.eTag(),
                    source.contentType(), source.bytes(), identityHash));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private CopyObjectResponse copySource(String objectKey, String identityHash, LegacyImageSource source) {
        try {
            return s3Client.copyObject(CopyObjectRequest.builder()
                    .destinationBucket(originalBucket).destinationKey(objectKey)
                    .sourceBucket(deliveryBucket).sourceKey(source.objectKey()).sourceVersionId(source.versionId())
                    .copySourceIfMatch(source.eTag())
                    .metadataDirective(MetadataDirective.REPLACE)
                    .metadata(Map.of(IDENTITY_METADATA_KEY, identityHash))
                    .taggingDirective(TaggingDirective.REPLACE).tagging("")
                    .contentType(source.contentType()).cacheControl("private, no-store")
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build());
        } catch (S3Exception e) {
            // copy에는 양쪽 bucket이 관여한다. 목적지 권한/설정 오류를 source 누락으로 단정하지 않는다.
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            boolean sourceMissing = "NoSuchKey".equals(code) || "NoSuchVersion".equals(code);
            if (e.statusCode() == 412) {
                throw new MediaBackfillStorageException(SOURCE_CHANGED);
            }
            throw new MediaBackfillStorageException(sourceMissing ? SOURCE_MISSING : STORAGE_UNAVAILABLE);
        }
    }

    private MediaBackfillStorageException inspectSourceFailure(S3Exception exception) {
        return new MediaBackfillStorageException(switch (exception.statusCode()) {
            case 404 -> SOURCE_MISSING;
            case 403 -> SOURCE_UNREADABLE;
            case 412 -> SOURCE_CHANGED;
            default -> STORAGE_UNAVAILABLE;
        });
    }

    private void assertOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("backfill storage must run outside a DB transaction");
        }
    }

    private record VersionMarker(String key, String version) {
    }
}
