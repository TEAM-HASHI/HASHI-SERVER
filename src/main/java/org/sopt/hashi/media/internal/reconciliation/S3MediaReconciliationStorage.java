package org.sopt.hashi.media.internal.reconciliation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationStorageException.Reason;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

public class S3MediaReconciliationStorage implements MediaReconciliationStorage {

    private final S3Client s3Client;
    private final String originalBucket;
    private final String deliveryBucket;

    public S3MediaReconciliationStorage(S3Client s3Client, String originalBucket, String deliveryBucket) {
        this.s3Client = Objects.requireNonNull(s3Client);
        this.originalBucket = requireBucket(originalBucket);
        this.deliveryBucket = requireBucket(deliveryBucket);
        if (this.originalBucket.equals(this.deliveryBucket)) {
            throw new IllegalArgumentException("reconciliation requires separate buckets");
        }
    }

    @Override
    public MediaObjectVersionPage listObjectVersions(
            MediaObjectLocation location,
            MediaObjectVersionCursor cursor,
            int pageSize
    ) {
        assertOutsideTransaction();
        requireNotInterrupted();
        Objects.requireNonNull(location);
        Objects.requireNonNull(cursor);
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("reconciliation page size must be between 1 and 1000");
        }
        String bucket = bucket(location);
        try {
            requireVersioningEnabled(bucket);
            ListObjectVersionsResponse response = s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(location.prefix())
                    .maxKeys(pageSize)
                    .keyMarker(cursor.keyMarker())
                    .versionIdMarker(cursor.versionIdMarker())
                    .build());
            return validatePage(response, location, bucket, cursor, pageSize);
        } catch (MediaReconciliationStorageException exception) {
            throw exception;
        } catch (SdkException exception) {
            preserveInterruption(exception);
            throw new MediaReconciliationStorageException(Thread.currentThread().isInterrupted()
                    ? Reason.INTERRUPTED : Reason.STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public void deleteObjectVersion(MediaObjectVersion object) {
        assertOutsideTransaction();
        requireNotInterrupted();
        Objects.requireNonNull(object);
        if ("null".equals(object.versionId())) {
            throw new MediaReconciliationStorageException(Reason.NON_IMMUTABLE_VERSION);
        }
        try {
            String bucket = bucket(object.location());
            requireVersioningEnabled(bucket);
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(object.objectKey())
                    .versionId(object.versionId())
                    .build());
        } catch (SdkException exception) {
            preserveInterruption(exception);
            throw new MediaReconciliationStorageException(Thread.currentThread().isInterrupted()
                    ? Reason.INTERRUPTED : Reason.DELETE_FAILED);
        }
    }

    @Override
    public void close() {
        s3Client.close();
    }

    private MediaObjectVersionPage validatePage(
            ListObjectVersionsResponse response,
            MediaObjectLocation location,
            String bucket,
            MediaObjectVersionCursor cursor,
            int pageSize
    ) {
        boolean invalid = response == null || response.isTruncated() == null
                || response.hasCommonPrefixes()
                || (response.name() != null && !bucket.equals(response.name()))
                || (response.prefix() != null && !location.prefix().equals(response.prefix()))
                || response.versions().size() + response.deleteMarkers().size() > pageSize;
        if (invalid) {
            throw new MediaReconciliationStorageException(Reason.INVALID_STORAGE_RESPONSE);
        }

        List<MediaObjectVersion> objects = new ArrayList<>(response.versions().size());
        Set<ObjectIdentity> identities = new HashSet<>();
        for (ObjectVersion version : response.versions()) {
            validateListedObject(version == null ? null : version.key(),
                    version == null ? null : version.versionId(), location, identities);
            if (version.lastModified() == null) {
                throw new MediaReconciliationStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
            objects.add(new MediaObjectVersion(location, version.key(), version.versionId(), version.lastModified()));
        }
        // delete marker 제거는 과거 object를 되살릴 수 있으므로 후보에 넣지 않되 응답 자체는 검증한다.
        for (DeleteMarkerEntry marker : response.deleteMarkers()) {
            validateListedObject(marker == null ? null : marker.key(),
                    marker == null ? null : marker.versionId(), location, identities);
        }

        MediaObjectVersionCursor next = null;
        if (Boolean.TRUE.equals(response.isTruncated())) {
            if (response.nextKeyMarker() == null || response.nextVersionIdMarker() == null) {
                throw new MediaReconciliationStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
            try {
                next = new MediaObjectVersionCursor(response.nextKeyMarker(), response.nextVersionIdMarker());
            } catch (IllegalArgumentException exception) {
                throw new MediaReconciliationStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
            if (next.equals(cursor)) {
                throw new MediaReconciliationStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
        }
        return new MediaObjectVersionPage(objects, next);
    }

    private void validateListedObject(String key, String versionId, MediaObjectLocation location,
                                      Set<ObjectIdentity> identities) {
        boolean invalid = key == null || !key.startsWith(location.prefix())
                || versionId == null || versionId.isBlank()
                || !identities.add(new ObjectIdentity(key, versionId));
        if (invalid) {
            throw new MediaReconciliationStorageException(Reason.INVALID_STORAGE_RESPONSE);
        }
    }

    private String bucket(MediaObjectLocation location) {
        return location == MediaObjectLocation.ORIGINAL ? originalBucket : deliveryBucket;
    }

    private void requireVersioningEnabled(String bucket) {
        GetBucketVersioningResponse response = s3Client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(bucket)
                .build());
        if (response == null || response.status() != BucketVersioningStatus.ENABLED) {
            throw new MediaReconciliationStorageException(Reason.VERSIONING_NOT_ENABLED);
        }
    }

    private static String requireBucket(String bucket) {
        boolean invalid = bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..");
        if (invalid) {
            throw new IllegalArgumentException("reconciliation requires a valid configured bucket name");
        }
        return bucket;
    }

    private static void assertOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("media reconciliation storage must run outside a DB transaction");
        }
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new MediaReconciliationStorageException(Reason.INTERRUPTED);
        }
    }

    private static void preserveInterruption(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            cause = cause.getCause();
        }
    }

    private record ObjectIdentity(String key, String versionId) {
    }
}
