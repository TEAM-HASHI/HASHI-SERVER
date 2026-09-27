package org.sopt.hashi.media.internal.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

class S3MediaReconciliationStorageTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final String KEY = "media/originals/" + ASSET_ID + "/original";
    private static final Instant MODIFIED = Instant.parse("2026-08-01T00:00:00Z");

    private final S3Client client = mock(S3Client.class);
    private final S3MediaReconciliationStorage storage =
            new S3MediaReconciliationStorage(client, "test-originals", "test-delivery");

    @BeforeEach
    void enableVersioning() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(versioning(BucketVersioningStatus.ENABLED));
    }

    @AfterEach
    void clearState() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    @Test
    void version_목록은_prefix와_cursor를_지정하고_delete_marker는_후보에서_제외한다() {
        ListObjectVersionsResponse response = ListObjectVersionsResponse.builder()
                .name("test-originals").prefix(MediaObjectLocation.ORIGINAL.prefix())
                .versions(version(KEY, "version-1"))
                .deleteMarkers(DeleteMarkerEntry.builder().key(KEY).versionId("marker-1").build())
                .isTruncated(true).nextKeyMarker(KEY).nextVersionIdMarker("version-1").build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(response);

        MediaObjectVersionPage page = storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100);

        assertThat(page.objects()).containsExactly(
                new MediaObjectVersion(MediaObjectLocation.ORIGINAL, KEY, "version-1", MODIFIED));
        assertThat(page.nextCursor()).isEqualTo(new MediaObjectVersionCursor(KEY, "version-1"));
        verify(client).listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket("test-originals").prefix("media/originals/")
                .encodingType(EncodingType.URL).maxKeys(100).build());
    }

    @Test
    void 두번째_page가_truncated인데_next_marker가_없으면_initial_cursor로_돌아가지_않는다() {
        MediaObjectVersionCursor secondPageCursor = new MediaObjectVersionCursor(KEY, "version-2");
        ListObjectVersionsResponse first = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "version-2"))
                .isTruncated(true)
                .nextKeyMarker(secondPageCursor.keyMarker())
                .nextVersionIdMarker(secondPageCursor.versionIdMarker())
                .build();
        ListObjectVersionsResponse brokenSecond = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "version-1"))
                .isTruncated(true)
                .build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(first, brokenSecond);

        MediaObjectVersionPage firstPage = storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100);

        assertThat(firstPage.nextCursor()).isEqualTo(secondPageCursor);
        assertThatThrownBy(() -> storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, firstPage.nextCursor(), 100))
                .isInstanceOf(MediaReconciliationStorageException.class)
                .extracting("reason")
                .isEqualTo(MediaReconciliationStorageException.Reason.INVALID_STORAGE_RESPONSE);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void truncated_page의_next_version_marker가_없으면_부분_cursor로_진행하지_않는다() {
        ListObjectVersionsResponse response = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "version-1"))
                .isTruncated(true)
                .nextKeyMarker(KEY)
                .build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100))
                .isInstanceOf(MediaReconciliationStorageException.class)
                .extracting("reason")
                .isEqualTo(MediaReconciliationStorageException.Reason.INVALID_STORAGE_RESPONSE);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void truncated_page의_next_key_marker가_없으면_부분_cursor로_진행하지_않는다() {
        ListObjectVersionsResponse response = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "version-1"))
                .isTruncated(true)
                .nextVersionIdMarker("version-2")
                .build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100))
                .isInstanceOf(MediaReconciliationStorageException.class)
                .extracting("reason")
                .isEqualTo(MediaReconciliationStorageException.Reason.INVALID_STORAGE_RESPONSE);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void 삭제는_목록에서_관측한_key와_version을_모두_명시한다() {
        MediaObjectVersion object = new MediaObjectVersion(
                MediaObjectLocation.ORIGINAL, KEY, "version-1", MODIFIED);

        storage.deleteObjectVersion(object);

        verify(client).deleteObject(DeleteObjectRequest.builder()
                .bucket("test-originals").key(KEY).versionId("version-1").build());
    }

    @Test
    void literal_null_version은_versioning이_enabled여도_삭제를_차단한다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(version(KEY, "null"))
                        .isTruncated(false)
                        .build());
        MediaObjectVersion object = storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100)
                .objects().getFirst();

        assertThatThrownBy(() -> storage.deleteObjectVersion(object))
                .isInstanceOf(MediaReconciliationStorageException.class)
                .extracting("reason")
                .isEqualTo(MediaReconciliationStorageException.Reason.NON_IMMUTABLE_VERSION);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void 목록_뒤_versioning이_중단되면_unique_version_삭제도_차단한다() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(versioning(BucketVersioningStatus.ENABLED),
                        versioning(BucketVersioningStatus.SUSPENDED));
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(version(KEY, "version-1"))
                        .isTruncated(false)
                        .build());
        MediaObjectVersion object = storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100)
                .objects().getFirst();

        assertThatThrownBy(() -> storage.deleteObjectVersion(object))
                .isInstanceOf(MediaReconciliationStorageException.class)
                .extracting("reason")
                .isEqualTo(MediaReconciliationStorageException.Reason.VERSIONING_NOT_ENABLED);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void 경로_version_시각이나_pagination이_불명확하면_삭제_후보를_반환하지_않는다() {
        List<ListObjectVersionsResponse> invalid = List.of(
                ListObjectVersionsResponse.builder().versions(version("legacy/path", "v1"))
                        .isTruncated(false).build(),
                ListObjectVersionsResponse.builder().versions(ObjectVersion.builder()
                                .key(KEY).versionId("v1").build())
                        .isTruncated(false).build(),
                ListObjectVersionsResponse.builder().versions(version(KEY, "v1"))
                        .isTruncated(true).build(),
                ListObjectVersionsResponse.builder()
                        .commonPrefixes(CommonPrefix.builder().prefix("media/originals/group/").build())
                        .isTruncated(false).build()
        );
        for (ListObjectVersionsResponse response : invalid) {
            when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(response);
            assertThatThrownBy(() -> storage.listObjectVersions(
                    MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100))
                    .isInstanceOf(MediaReconciliationStorageException.class)
                    .extracting("reason")
                    .isEqualTo(MediaReconciliationStorageException.Reason.INVALID_STORAGE_RESPONSE);
        }
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void DB_transaction_중에는_S3_목록과_삭제를_호출하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        MediaObjectVersion object = new MediaObjectVersion(
                MediaObjectLocation.ORIGINAL, KEY, "version-1", MODIFIED);

        assertThatThrownBy(() -> storage.listObjectVersions(
                MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.deleteObjectVersion(object)).isInstanceOf(IllegalStateException.class);
        verify(client, never()).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    private ObjectVersion version(String key, String versionId) {
        return ObjectVersion.builder().key(key).versionId(versionId).lastModified(MODIFIED).build();
    }

    private GetBucketVersioningResponse versioning(BucketVersioningStatus status) {
        return GetBucketVersioningResponse.builder().status(status).build();
    }
}
