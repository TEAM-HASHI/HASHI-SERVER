package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
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

class S3MediaBackfillStorageTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final String IDENTITY = "a".repeat(64);
    private static final String KEY = "media/originals/" + ASSET_ID + "/original";

    private final S3Client s3Client = mock(S3Client.class);
    private final S3MediaBackfillStorage storage = new S3MediaBackfillStorage(
            s3Client, "test-delivery", "test-originals");

    @AfterEach
    void 트랜잭션_테스트_상태를_정리한다() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void legacy_HEAD는_설정된_delivery_bucket만_사용한다() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .versionId("null").eTag("\"etag\"").contentType("image/jpeg").contentLength(1024L).build());

        LegacyImageSource source = storage.inspectSource("legacy/photo.jpg");

        assertThat(source.versionId()).isNull();
        assertThat(source.bytes()).isEqualTo(1024);
        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-delivery");
        assertThat(request.getValue().key()).isEqualTo("legacy/photo.jpg");
    }

    @Test
    void 정확한_source_version과_ETag로_복사하고_부가정보는_승계하지_않는다() {
        arrangeEmptyDestination();
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().versionId("copy-v1").build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(copyHead("copy-v1", IDENTITY));
        LegacyImageSource source = source("legacy/a b+한글.jpg", "v+/?=", "\"original-etag\"");

        BackfillOriginalCopy copy = storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source);

        assertThat(copy.versionId()).isEqualTo("copy-v1");
        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(request.capture());
        CopyObjectRequest value = request.getValue();
        assertThat(value.sourceBucket()).isEqualTo("test-delivery");
        assertThat(value.sourceKey()).isEqualTo("legacy/a b+한글.jpg");
        assertThat(value.sourceVersionId()).isEqualTo("v+/?=");
        assertThat(value.copySourceIfMatch()).isEqualTo("\"original-etag\"");
        assertThat(value.destinationBucket()).isEqualTo("test-originals");
        assertThat(value.destinationKey()).isEqualTo(KEY);
        assertThat(value.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
        assertThat(value.metadata()).containsExactlyEntriesOf(Map.of("backfill-identity", IDENTITY));
        assertThat(value.taggingDirective()).isEqualTo(TaggingDirective.REPLACE);
        assertThat(value.tagging()).isEmpty();
        assertThat(value.overrideConfiguration().orElseThrow().headers())
                .containsEntry("x-amz-object-annotation-directive", List.of("EXCLUDE"));
        assertThat(value.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(value.cacheControl()).isEqualTo("private, no-store");
        ArgumentCaptor<HeadObjectRequest> head = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(head.capture());
        assertThat(head.getValue().versionId()).isEqualTo("copy-v1");
    }

    @Test
    void version이_없는_source는_ETag_조건만_사용한다() {
        arrangeEmptyDestination();
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().versionId("copy-v1").build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(copyHead("copy-v1", IDENTITY));

        storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source("legacy/a.jpg", null, "\"etag\""));

        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(request.capture());
        assertThat(request.getValue().sourceKey()).isEqualTo("legacy/a.jpg");
        assertThat(request.getValue().sourceVersionId()).isNull();
        assertThat(request.getValue().copySourceIfMatch()).isEqualTo("\"etag\"");
    }

    @Test
    void copy_후_중단되어도_동일_identity의_목적지_version을_재사용한다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().versions(version(KEY, "existing")).build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(copyHead("existing", IDENTITY));

        BackfillOriginalCopy copy = storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source());

        assertThat(copy.versionId()).isEqualTo("existing");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void copy_응답이_유실돼도_재호출은_기존_version을_찾아_중복_copy를_피한다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().build())
                .thenReturn(ListObjectVersionsResponse.builder().versions(version(KEY, "copied")).build());
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(SdkClientException.create("response lost"));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(copyHead("copied", IDENTITY));

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "STORAGE_UNAVAILABLE");
        assertThat(storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()).versionId()).isEqualTo("copied");
        verify(s3Client).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void identity가_같아도_복사본의_길이나_MIME이_다르면_재사용하지_않는다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().versions(version(KEY, "existing")).build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(copyHead("existing", IDENTITY).toBuilder().contentLength(2048L).build())
                .thenReturn(copyHead("existing", IDENTITY).toBuilder().contentType("image/png").build());

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "COPY_CONFLICT");
        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "COPY_CONFLICT");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void version_페이지를_이어가고_비슷한_prefix의_다른_key는_재사용하지_않는다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(version(KEY + "-other", "wrong"))
                        .isTruncated(true).nextKeyMarker(KEY).nextVersionIdMarker("marker").build())
                .thenReturn(ListObjectVersionsResponse.builder().versions(version(KEY, "existing")).build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(copyHead("existing", IDENTITY));

        assertThat(storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()).versionId()).isEqualTo("existing");

        ArgumentCaptor<ListObjectVersionsRequest> request = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3Client, org.mockito.Mockito.times(2)).listObjectVersions(request.capture());
        assertThat(request.getAllValues().get(1).keyMarker()).isEqualTo(KEY);
        assertThat(request.getAllValues().get(1).versionIdMarker()).isEqualTo("marker");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void 목록_조회_후_사라진_version은_다음_version에서_복구한다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(version(KEY, "removed"), version(KEY, "existing")).build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(s3Failure(404)).thenReturn(copyHead("existing", IDENTITY));

        assertThat(storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()).versionId()).isEqualTo("existing");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void 다른_identity의_복사본을_덮어쓰지_않는다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().versions(version(KEY, "existing")).build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(copyHead("existing", "b".repeat(64)));

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "COPY_CONFLICT");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void 목적지_versioning이_꺼져_있으면_작업을_고정하지_않는다() {
        arrangeEmptyDestination();
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().versionId("null").build());

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "COPY_CONFLICT");
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @ParameterizedTest
    @CsvSource({"412,SOURCE_CHANGED", "404,STORAGE_UNAVAILABLE", "403,STORAGE_UNAVAILABLE", "503,STORAGE_UNAVAILABLE"})
    void copy_실패는_고정_원인으로_구분하고_AWS_원문은_노출하지_않는다(int status, String reason) {
        arrangeEmptyDestination();
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(s3Failure(status));

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), reason);
    }

    @Test
    void copy가_source_key_누락을_명시한_경우만_누락으로_분류한다() {
        arrangeEmptyDestination();
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(404);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build());
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow((S3Exception) builder.build());

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "SOURCE_MISSING");
    }

    @ParameterizedTest
    @CsvSource({"404,SOURCE_MISSING", "403,SOURCE_UNREADABLE", "503,STORAGE_UNAVAILABLE"})
    void source_HEAD_오류도_고정_원인으로_반환한다(int status, String reason) {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Failure(status));

        assertStorageFailure(() -> storage.inspectSource("legacy/a.jpg"), reason);
    }

    @Test
    void SDK_오류와_목적지_권한오류는_source_누락으로_오인하지_않는다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenThrow(SdkClientException.create("sensitive provider detail"));
        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "STORAGE_UNAVAILABLE");

        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenThrow(s3Failure(403));
        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "STORAGE_UNAVAILABLE");
    }

    @Test
    void 반복되는_pagination_marker면_중단하고_새_copy를_만들지_않는다() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(true).nextKeyMarker(KEY).nextVersionIdMarker("same").build());

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()), "STORAGE_UNAVAILABLE");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void 다른_bucket과_잘못된_identity는_S3_호출_전에_거부한다() {
        LegacyImageSource otherBucket = new LegacyImageSource(
                "other-bucket", "legacy/a.jpg", null, "etag", "image/jpeg", 1024);

        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, otherBucket), "INVALID_SOURCE");
        assertStorageFailure(() -> storage.findOrCopyOriginal(ASSET_ID, "invalid", source()), "INVALID_SOURCE");
        assertStorageFailure(() -> storage.inspectSource("media/renditions/a.webp"), "INVALID_SOURCE");
        verifyNoInteractions(s3Client);
    }

    @Test
    void DB_트랜잭션_안에서는_HEAD와_copy를_호출하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> storage.inspectSource("legacy/a.jpg"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(s3Client);
    }

    private void arrangeEmptyDestination() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().build());
    }

    private LegacyImageSource source() {
        return source("legacy/a.jpg", null, "\"original-etag\"");
    }

    private LegacyImageSource source(String key, String version, String eTag) {
        return new LegacyImageSource("test-delivery", key, version, eTag, "image/jpeg", 1024);
    }

    private ObjectVersion version(String key, String versionId) {
        return ObjectVersion.builder().key(key).versionId(versionId).build();
    }

    private HeadObjectResponse copyHead(String versionId, String identity) {
        return HeadObjectResponse.builder().versionId(versionId).eTag("\"copy-etag\"")
                .contentType("image/jpeg").contentLength(1024L)
                .metadata(Map.of("backfill-identity", identity)).build();
    }

    private S3Exception s3Failure(int status) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(status);
        builder.message("sensitive provider detail");
        return (S3Exception) builder.build();
    }

    private void assertStorageFailure(Runnable action, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MediaBackfillStorageException.class, error -> {
            assertThat(error.getReason().name()).isEqualTo(reason);
            assertThat(error.getMessage()).isEqualTo("media backfill storage: " + reason);
            assertThat(error.getCause()).isNull();
        });
    }
}
