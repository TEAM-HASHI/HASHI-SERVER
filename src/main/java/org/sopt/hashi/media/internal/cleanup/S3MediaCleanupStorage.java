package org.sopt.hashi.media.internal.cleanup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException.Reason;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

public class S3MediaCleanupStorage implements MediaCleanupStorage {

    private static final int MAX_DELETE_BATCH_SIZE = 1000;
    private static final int MAX_PAGE_LIMIT = 100;

    private final S3Client s3Client;
    private final String originalBucket;
    private final String deliveryBucket;
    private final int maxPagesPerPrefix;
    private final int maxKeysPerPage;
    private final Duration workBudget;
    private final LongSupplier nanoTime;

    public S3MediaCleanupStorage(S3Client s3Client, String originalBucket, String deliveryBucket,
                                 int maxPagesPerPrefix, int maxKeysPerPage) {
        this(s3Client, originalBucket, deliveryBucket, maxPagesPerPrefix, maxKeysPerPage, Duration.ofMinutes(1));
    }

    public S3MediaCleanupStorage(S3Client s3Client, String originalBucket, String deliveryBucket,
                                 int maxPagesPerPrefix, int maxKeysPerPage, Duration workBudget) {
        this(s3Client, originalBucket, deliveryBucket, maxPagesPerPrefix, maxKeysPerPage,
                workBudget, System::nanoTime);
    }

    S3MediaCleanupStorage(S3Client s3Client, String originalBucket, String deliveryBucket,
                          int maxPagesPerPrefix, int maxKeysPerPage, Duration workBudget, LongSupplier nanoTime) {
        this.s3Client = Objects.requireNonNull(s3Client);
        this.originalBucket = requireBucket(originalBucket);
        this.deliveryBucket = requireBucket(deliveryBucket);
        if (originalBucket.equals(deliveryBucket)) {
            throw new IllegalArgumentException("cleanup requires separate original and delivery buckets");
        }
        if (maxPagesPerPrefix < 1 || maxPagesPerPrefix > MAX_PAGE_LIMIT) {
            throw new IllegalArgumentException("cleanup page limit must be between 1 and 100");
        }
        if (maxKeysPerPage < 1 || maxKeysPerPage > MAX_DELETE_BATCH_SIZE) {
            throw new IllegalArgumentException("cleanup page size must be between 1 and 1000");
        }
        this.maxPagesPerPrefix = maxPagesPerPrefix;
        this.maxKeysPerPage = maxKeysPerPage;
        this.workBudget = Objects.requireNonNull(workBudget);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (workBudget.isNegative() || workBudget.isZero() || workBudget.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("cleanup storage work budget must be positive and at most 5 minutes");
        }
    }

    @Override
    public MediaObjectPurgeResult purgeAssetObjects(UUID assetId) {
        Objects.requireNonNull(assetId, "asset id is required");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("media cleanup storage must run outside a DB transaction");
        }
        requireNotInterrupted();
        MediaCleanupWorkBudget budget = new MediaCleanupWorkBudget(workBudget, nanoTime);
        try {
            MediaObjectPurgeResult original = purgePrefix(
                    originalBucket, "media/originals/%s/".formatted(assetId), budget);
            MediaObjectPurgeResult delivery = purgePrefix(
                    deliveryBucket, "media/renditions/%s/".formatted(assetId), budget);
            requireNotInterrupted();
            return new MediaObjectPurgeResult(original.complete() && delivery.complete(),
                    original.acknowledgedDeletes() + delivery.acknowledgedDeletes());
        } catch (SdkException exception) {
            preserveInterruption(exception);
            requireNotInterrupted();
            throw new MediaCleanupStorageException(Reason.STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public void close() {
        s3Client.close();
    }

    private MediaObjectPurgeResult purgePrefix(String bucket, String prefix, MediaCleanupWorkBudget budget) {
        int acknowledgedDeletes = 0;
        for (int page = 0; page < maxPagesPerPrefix; page++) {
            requireNotInterrupted();
            if (!budget.hasTimeLeft()) {
                return new MediaObjectPurgeResult(false, acknowledgedDeletes);
            }
            requireVersioningEnabled(bucket);
            // 삭제한 key/version을 다음 페이지 marker로 재사용하지 않고 남은 첫 페이지를 다시 읽는다.
            ListObjectVersionsResponse response = s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket).prefix(prefix).maxKeys(maxKeysPerPage).build());
            List<ObjectIdentifier> objects = validatedObjects(response, bucket, prefix);
            if (objects.isEmpty()) {
                if (Boolean.TRUE.equals(response.isTruncated())) {
                    throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
                }
                return new MediaObjectPurgeResult(true, acknowledgedDeletes);
            }
            requireNotInterrupted();
            if (!budget.hasTimeLeft()) {
                return new MediaObjectPurgeResult(false, acknowledgedDeletes);
            }
            requireVersioningEnabled(bucket);
            DeleteObjectsResponse deleted = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket).delete(Delete.builder().objects(objects).quiet(true).build()).build());
            if (deleted == null) {
                throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
            // quiet 응답은 성공 목록을 생략한다. HTTP 200이어도 항목별 오류가 있으면 완료가 아니다.
            if (deleted.hasErrors()) {
                throw new MediaCleanupStorageException(Reason.PARTIAL_DELETE);
            }
            acknowledgedDeletes += objects.size();
        }
        // 마지막 DELETE가 성공해도 빈 목록을 관측하기 전에는 DB 정리 완료를 허용하지 않는다.
        return new MediaObjectPurgeResult(false, acknowledgedDeletes);
    }

    private void requireVersioningEnabled(String bucket) {
        GetBucketVersioningResponse response = s3Client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(bucket)
                .build());
        if (response == null || response.status() != BucketVersioningStatus.ENABLED) {
            throw new MediaCleanupStorageException(Reason.VERSIONING_NOT_ENABLED);
        }
    }

    private List<ObjectIdentifier> validatedObjects(ListObjectVersionsResponse response,
                                                    String bucket, String prefix) {
        boolean invalidResponse = response == null || response.isTruncated() == null
                || response.hasCommonPrefixes()
                || (response.name() != null && !bucket.equals(response.name()))
                || (response.prefix() != null && !prefix.equals(response.prefix()));
        if (invalidResponse) {
            throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
        }
        int count = response.versions().size() + response.deleteMarkers().size();
        if (count > maxKeysPerPage) {
            throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
        }
        List<ObjectIdentifier> objects = new ArrayList<>(count);
        for (ObjectVersion version : response.versions()) {
            if (version == null) {
                throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
            objects.add(validatedObject(version.key(), version.versionId(), prefix));
        }
        for (DeleteMarkerEntry marker : response.deleteMarkers()) {
            if (marker == null) {
                throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
            }
            objects.add(validatedObject(marker.key(), marker.versionId(), prefix));
        }
        Set<ObjectIdentifier> unique = new HashSet<>(objects);
        if (unique.size() != objects.size()) {
            throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
        }
        return objects;
    }

    private ObjectIdentifier validatedObject(String key, String versionId, String prefix) {
        boolean invalidObject = key == null || !key.startsWith(prefix)
                || versionId == null || versionId.isBlank();
        if (invalidObject) {
            throw new MediaCleanupStorageException(Reason.INVALID_STORAGE_RESPONSE);
        }
        // versioning이 없던 delivery object의 리터럴 "null" version도 명시해 삭제한다.
        return ObjectIdentifier.builder().key(key).versionId(versionId).build();
    }

    private static String requireBucket(String bucket) {
        boolean invalidBucket = bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..");
        if (invalidBucket) {
            throw new IllegalArgumentException("cleanup requires a valid configured bucket name");
        }
        return bucket;
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new MediaCleanupStorageException(Reason.INTERRUPTED);
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
}
