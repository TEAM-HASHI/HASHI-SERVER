package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException.Reason;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Error;

class S3MediaCleanupStorageTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final String ORIGINAL_PREFIX = "media/originals/" + ASSET_ID + "/";
    private static final String ORIGINAL_KEY = ORIGINAL_PREFIX + "original";
    private static final String DELIVERY_PREFIX = "media/renditions/" + ASSET_ID + "/";
    private static final String DELIVERY_KEY = DELIVERY_PREFIX + "v1/review-preview/135.webp";
    private static final ListObjectVersionsResponse EMPTY = ListObjectVersionsResponse.builder()
            .isTruncated(false).build();
    private static final DeleteObjectsResponse DELETED = DeleteObjectsResponse.builder().build();

    private final S3Client client = mock(S3Client.class);
    private final S3MediaCleanupStorage storage = new S3MediaCleanupStorage(
            client, "test-originals", "test-delivery", 3, 1000);

    @BeforeEach
    void versioning을_활성화한다() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(versioning(BucketVersioningStatus.ENABLED));
    }

    @AfterEach
    void 테스트의_트랜잭션과_중단_상태를_정리한다() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    @Test
    void 두_asset_prefix가_모두_비었을_때만_완료한다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(EMPTY);

        MediaObjectPurgeResult result = storage.purgeAssetObjects(ASSET_ID);

        assertThat(result).isEqualTo(new MediaObjectPurgeResult(true, 0));
        ArgumentCaptor<ListObjectVersionsRequest> requests = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(client, times(2)).listObjectVersions(requests.capture());
        assertThat(requests.getAllValues()).extracting(ListObjectVersionsRequest::bucket)
                .containsExactly("test-originals", "test-delivery");
        assertThat(requests.getAllValues()).extracting(ListObjectVersionsRequest::prefix)
                .containsExactly(ORIGINAL_PREFIX, DELIVERY_PREFIX);
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.maxKeys()).isEqualTo(1000);
            assertThat(request.delimiter()).isNull();
            assertThat(request.keyMarker()).isNull();
            assertThat(request.versionIdMarker()).isNull();
        });
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 목록_뒤_versioning이_중단되면_기존_null_version도_삭제하지_않는다() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(versioning(BucketVersioningStatus.ENABLED),
                        versioning(BucketVersioningStatus.SUSPENDED));
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "null")));

        assertThatThrownBy(() -> storage.purgeAssetObjects(ASSET_ID))
                .isInstanceOf(MediaCleanupStorageException.class)
                .extracting("reason")
                .isEqualTo(Reason.VERSIONING_NOT_ENABLED);
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 원본의_모든_version과_삭제_marker_파생본의_null_version을_정확히_지정한다() {
        ListObjectVersionsResponse originals = page(version(ORIGINAL_KEY, "older"), version(ORIGINAL_KEY, "newer"))
                .toBuilder().deleteMarkers(marker(ORIGINAL_KEY, "delete-marker")).build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(originals, EMPTY, page(version(DELIVERY_KEY, "null")), EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DELETED);

        assertThat(storage.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 4));

        ArgumentCaptor<DeleteObjectsRequest> requests = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client, times(2)).deleteObjects(requests.capture());
        assertThat(requests.getAllValues().getFirst().bucket()).isEqualTo("test-originals");
        assertThat(requests.getAllValues().getFirst().delete().objects())
                .containsExactly(object(ORIGINAL_KEY, "older"), object(ORIGINAL_KEY, "newer"),
                        object(ORIGINAL_KEY, "delete-marker"));
        assertThat(requests.getAllValues().getLast().bucket()).isEqualTo("test-delivery");
        assertThat(requests.getAllValues().getLast().delete().objects())
                .containsExactly(object(DELIVERY_KEY, "null"));
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.delete().quiet()).isTrue();
            assertThat(request.mfa()).isNull();
            assertThat(request.bypassGovernanceRetention()).isNull();
        });
    }

    @Test
    void 이미_삭제한_asset의_재호출은_추가_삭제없이_완료한다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1")), EMPTY, EMPTY, EMPTY, EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DELETED);

        assertThat(storage.purgeAssetObjects(ASSET_ID).acknowledgedDeletes()).isEqualTo(1);
        assertThat(storage.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 0));

        verify(client).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 삭제한_marker를_넘기지_않고_남은_첫_페이지를_한도_내에서_다시_읽는다() {
        S3MediaCleanupStorage bounded = new S3MediaCleanupStorage(client, "test-originals", "test-delivery", 3, 2);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1"), version(ORIGINAL_KEY, "v2"))
                                .toBuilder().isTruncated(true).nextKeyMarker(ORIGINAL_KEY)
                                .nextVersionIdMarker("v2").build(),
                        page(version(ORIGINAL_KEY, "v3")), EMPTY, EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DELETED);

        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 3));

        ArgumentCaptor<ListObjectVersionsRequest> requests = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(client, times(4)).listObjectVersions(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.maxKeys()).isEqualTo(2);
            assertThat(request.keyMarker()).isNull();
            assertThat(request.versionIdMarker()).isNull();
        });
    }

    @Test
    void 삭제가_성공해도_빈_목록을_확인하기_전에_한도가_끝나면_미완료다() {
        S3MediaCleanupStorage bounded = new S3MediaCleanupStorage(client, "test-originals", "test-delivery", 1, 1);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1")), EMPTY, EMPTY, EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DELETED);

        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(false, 1));
        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 0));

        verify(client).deleteObjects(any(DeleteObjectsRequest.class));
        verify(client, times(4)).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void 경로와_version이_불명확하거나_모순된_목록은_삭제하지_않는다(ListObjectVersionsResponse response) {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(response);

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.INVALID_STORAGE_RESPONSE);

        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 페이지_크기를_넘긴_응답도_삭제하기_전에_거부한다() {
        S3MediaCleanupStorage bounded = new S3MediaCleanupStorage(client, "test-originals", "test-delivery", 1, 1);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1"), version(ORIGINAL_KEY, "v2")));

        assertFailure(() -> bounded.purgeAssetObjects(ASSET_ID), Reason.INVALID_STORAGE_RESPONSE);
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void HTTP_200의_항목별_삭제_실패는_완료로_처리하지_않고_재호출한다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "gone"), version(ORIGINAL_KEY, "retained")),
                        page(version(ORIGINAL_KEY, "retained")), EMPTY, EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().errors(S3Error.builder()
                                .key(ORIGINAL_KEY).versionId("retained").code("AccessDenied")
                                .message("private provider detail").build()).build(), DELETED);

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.PARTIAL_DELETE);
        assertThat(storage.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 1));

        ArgumentCaptor<DeleteObjectsRequest> requests = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client, times(2)).deleteObjects(requests.capture());
        assertThat(requests.getAllValues().getLast().delete().objects())
                .containsExactly(object(ORIGINAL_KEY, "retained"));
    }

    @Test
    void SDK_원문과_원인_예외는_호출자에게_노출하지_않는다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenThrow(SdkClientException.create("private bucket/key/version"));

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.STORAGE_UNAVAILABLE);
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 삭제_응답이_유실되면_완료하지_않고_다음_실행에서_빈_목록을_확인한다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1")), EMPTY, EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenThrow(SdkClientException.create("response lost after deletion"));

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.STORAGE_UNAVAILABLE);
        assertThat(storage.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 0));
    }

    @Test
    void 삭제_결과가_없으면_완료로_간주하지_않는다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1")));

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.INVALID_STORAGE_RESPONSE);
        verify(client).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @Test
    void 이미_중단된_스레드는_S3를_호출하지_않는다() {
        Thread.currentThread().interrupt();

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void SDK_내부_중단도_플래그를_복원하고_다음_삭제를_멈춘다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenThrow(
                SdkClientException.create("private detail", new InterruptedException("private cause")));

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 삭제_직후_중단되면_다른_prefix로_진행하지_않는다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1")));
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return DELETED;
        });

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(client).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(client).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 마지막_조회에서_중단됐어도_정리_완료를_반환하지_않는다() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(EMPTY)
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return EMPTY;
                });

        assertFailure(() -> storage.purgeAssetObjects(ASSET_ID), Reason.INTERRUPTED);
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void DB_트랜잭션_안에서는_목록_조회도_허용하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> storage.purgeAssetObjects(ASSET_ID)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    @Test
    void 유효하지_않은_bucket과_처리량은_클라이언트_호출_전에_거부한다() {
        for (String bucket : List.of("", " test-originals", "s3://test-originals", "test..originals")) {
            assertThatThrownBy(() -> new S3MediaCleanupStorage(client, bucket, "test-delivery", 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new S3MediaCleanupStorage(client, null, "test-delivery", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3MediaCleanupStorage(client, "same-bucket", "same-bucket", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        for (int limit : List.of(-1, 0, 101)) {
            assertThatThrownBy(() -> new S3MediaCleanupStorage(client, "test-originals", "test-delivery", limit, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int size : List.of(-1, 0, 1001)) {
            assertThatThrownBy(() -> new S3MediaCleanupStorage(client, "test-originals", "test-delivery", 1, size))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storage.purgeAssetObjects(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(client);
    }

    @Test
    void 종료할_때_S3_클라이언트를_닫는다() {
        storage.close();

        verify(client).close();
    }

    @Test
    void 목록_조회_중_시간이_끝나면_삭제를_시작하지_않는다() {
        AtomicLong clock = new AtomicLong();
        S3MediaCleanupStorage bounded = budgetStorage(clock);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(invocation -> {
            clock.set(Duration.ofSeconds(1).toNanos());
            return page(version(ORIGINAL_KEY, "v1"));
        });

        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(false, 0));
        verify(client).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 삭제_중_시간이_끝나면_성과는_남기되_다음_호출에서_완료를_확인한다() {
        AtomicLong clock = new AtomicLong();
        S3MediaCleanupStorage bounded = budgetStorage(clock);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(page(version(ORIGINAL_KEY, "v1")), EMPTY, EMPTY);
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(invocation -> {
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            return DELETED;
        });

        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(false, 1));
        verify(client).listObjectVersions(any(ListObjectVersionsRequest.class));
        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 0));
        verify(client, times(3)).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(client).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 원본과_파생본은_별도_시간이_아니라_같은_시간_한도를_사용한다() {
        AtomicLong clock = new AtomicLong();
        S3MediaCleanupStorage bounded = budgetStorage(clock);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(invocation -> {
            clock.set(Duration.ofSeconds(1).toNanos());
            return EMPTY;
        });

        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(false, 0));
        verify(client).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void 마지막_빈_목록_응답으로_이미_확인한_완료는_시간_초과로_버리지_않는다() {
        AtomicLong clock = new AtomicLong();
        S3MediaCleanupStorage bounded = budgetStorage(clock);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(EMPTY)
                .thenAnswer(invocation -> {
                    clock.set(Duration.ofSeconds(1).toNanos());
                    return EMPTY;
                });

        assertThat(bounded.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 0));
        verify(client, times(2)).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    private S3MediaCleanupStorage budgetStorage(AtomicLong clock) {
        return new S3MediaCleanupStorage(client, "test-originals", "test-delivery", 3, 1000,
                Duration.ofSeconds(1), clock::get);
    }

    private static Stream<ListObjectVersionsResponse> invalidPages() {
        return Stream.of(
                null,
                ListObjectVersionsResponse.builder().build(),
                EMPTY.toBuilder().isTruncated(true).build(),
                page(version("legacy/photo.jpg", "v1")),
                page(version(ORIGINAL_PREFIX.replace("/originals/", "/renditions/"), "v1")),
                page(version("media/originals/" + ASSET_ID + "-other/original", "v1")),
                page(version(null, "v1")),
                page(version(ORIGINAL_KEY, null)),
                page(version(ORIGINAL_KEY, " ")),
                page(version(ORIGINAL_KEY, "v1")).toBuilder().name("other-bucket").build(),
                page(version(ORIGINAL_KEY, "v1")).toBuilder().prefix("media/originals/").build(),
                page(version(ORIGINAL_KEY, "v1"), version(ORIGINAL_KEY, "v1")),
                EMPTY.toBuilder().deleteMarkers(marker("legacy/photo.jpg", "marker")).build(),
                EMPTY.toBuilder().deleteMarkers(marker(ORIGINAL_KEY, null)).build(),
                EMPTY.toBuilder().commonPrefixes(CommonPrefix.builder().prefix(ORIGINAL_PREFIX).build()).build()
        );
    }

    private static ListObjectVersionsResponse page(ObjectVersion... versions) {
        return ListObjectVersionsResponse.builder().isTruncated(false).versions(versions).build();
    }

    private static ObjectVersion version(String key, String versionId) {
        return ObjectVersion.builder().key(key).versionId(versionId).build();
    }

    private static DeleteMarkerEntry marker(String key, String versionId) {
        return DeleteMarkerEntry.builder().key(key).versionId(versionId).build();
    }

    private static ObjectIdentifier object(String key, String versionId) {
        return ObjectIdentifier.builder().key(key).versionId(versionId).build();
    }

    private static GetBucketVersioningResponse versioning(BucketVersioningStatus status) {
        return GetBucketVersioningResponse.builder().status(status).build();
    }

    private void assertFailure(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MediaCleanupStorageException.class, error -> {
            assertThat(error.getReason()).isEqualTo(reason);
            assertThat(error.getMessage()).isEqualTo("media cleanup storage: " + reason);
            assertThat(error.getCause()).isNull();
        });
    }
}
