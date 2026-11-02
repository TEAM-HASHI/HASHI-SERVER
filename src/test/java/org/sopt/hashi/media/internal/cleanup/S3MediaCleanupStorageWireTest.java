package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException.Reason;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class S3MediaCleanupStorageWireTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final String ORIGINAL_PREFIX = "media/originals/" + ASSET_ID + "/";
    private static final String ORIGINAL_KEY = ORIGINAL_PREFIX + "original";
    private static final String DELIVERY_PREFIX = "media/renditions/" + ASSET_ID + "/";
    private static final String DELIVERY_KEY = DELIVERY_PREFIX + "v1/review-preview/135.webp";
    private static final String EMPTY = """
            <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <IsTruncated>false</IsTruncated>
            </ListVersionsResult>
            """;
    private static final String VERSIONING_ENABLED = """
            <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Status>Enabled</Status>
            </VersioningConfiguration>
            """;

    @Test
    void 실제_SDK가_개별_DELETE의_query에_version과_marker_null_version을_정확히_전달한다() throws Exception {
        String originalPage = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Name>test-originals</Name><Prefix>%s</Prefix><IsTruncated>false</IsTruncated>
                    <Version><Key>%s</Key><VersionId>v+/?=&amp;</VersionId><IsLatest>false</IsLatest></Version>
                    <DeleteMarker><Key>%s</Key><VersionId>marker-v1</VersionId><IsLatest>true</IsLatest></DeleteMarker>
                </ListVersionsResult>
                """.formatted(ORIGINAL_PREFIX, ORIGINAL_KEY, ORIGINAL_KEY);
        String deliveryPage = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Name>test-delivery</Name><Prefix>%s</Prefix><IsTruncated>false</IsTruncated>
                    <Version><Key>%s</Key><VersionId>null</VersionId><IsLatest>true</IsLatest></Version>
                </ListVersionsResult>
                """.formatted(DELIVERY_PREFIX, DELIVERY_KEY);
        SdkHttpClient transport = mock(SdkHttpClient.class);
        AtomicInteger responseIndex = new AtomicInteger();
        List<ExecutableHttpRequest> responses = List.of(
                response(VERSIONING_ENABLED), response(originalPage),
                response(VERSIONING_ENABLED), response(204, ""),
                response(VERSIONING_ENABLED), response(204, ""),
                response(VERSIONING_ENABLED), response(EMPTY),
                response(VERSIONING_ENABLED), response(deliveryPage),
                response(VERSIONING_ENABLED), response(204, ""),
                response(VERSIONING_ENABLED), response(EMPTY));
        when(transport.prepareRequest(any(HttpExecuteRequest.class))).thenAnswer(invocation -> {
            return responses.get(responseIndex.getAndIncrement());
        });

        // 실제 SDK 직렬화·서명·XML 역직렬화를 사용하되 HTTP 전송은 모킹하여 AWS에 접속하지 않는다.
        try (S3Client client = client(transport)) {
            S3MediaCleanupStorage storage = new S3MediaCleanupStorage(
                    client, "test-originals", "test-delivery", 3, 1000);

            assertThat(storage.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 3));
        }

        ArgumentCaptor<HttpExecuteRequest> captured = ArgumentCaptor.forClass(HttpExecuteRequest.class);
        verify(transport, times(14)).prepareRequest(captured.capture());
        List<HttpExecuteRequest> deletes = captured.getAllValues().stream()
                .filter(request -> request.httpRequest().method() == SdkHttpMethod.DELETE).toList();
        assertThat(deletes).hasSize(3);
        assertThat(deletes).extracting(request -> request.httpRequest().encodedPath())
                .containsExactly("/test-originals/" + ORIGINAL_KEY, "/test-originals/" + ORIGINAL_KEY,
                        "/test-delivery/" + DELIVERY_KEY);
        assertThat(deletes).extracting(request -> request.httpRequest().rawQueryParameters().get("versionId"))
                .containsExactly(List.of("v+/?=&"), List.of("marker-v1"), List.of("null"));
        assertThat(deletes.getFirst().httpRequest().getUri().getRawQuery()).contains("versionId=v%2B%2F%3F%3D%26");
        assertThat(deletes).allSatisfy(request -> assertThat(requestBody(request)).isEmpty());
        assertThat(captured.getAllValues()).allSatisfy(request -> {
            assertThat(request.httpRequest().rawQueryParameters())
                    .doesNotContainKeys("delete", "key-marker", "version-id-marker");
            assertThat(request.httpRequest().firstMatchingHeader("x-amz-bypass-governance-retention")).isEmpty();
            assertThat(request.httpRequest().firstMatchingHeader("x-amz-mfa")).isEmpty();
        });
    }

    @Test
    void SDK가_역직렬화한_삭제_권한_거절은_완료로_처리하지_않는다() throws IOException {
        SdkHttpClient transport = mock(SdkHttpClient.class);
        String page = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <IsTruncated>false</IsTruncated>
                    <Version><Key>%s</Key><VersionId>v1</VersionId></Version>
                </ListVersionsResult>
                """.formatted(ORIGINAL_KEY);
        String failure = """
                <Error><Code>AccessDenied</Code><Message>private-provider-message</Message></Error>
                """;
        ExecutableHttpRequest pageResponse = response(page);
        ExecutableHttpRequest failureResponse = response(403, failure);
        ExecutableHttpRequest listVersioningResponse = response(VERSIONING_ENABLED);
        ExecutableHttpRequest deleteVersioningResponse = response(VERSIONING_ENABLED);
        when(transport.prepareRequest(any(HttpExecuteRequest.class)))
                .thenReturn(listVersioningResponse, pageResponse, deleteVersioningResponse, failureResponse);

        try (S3Client client = client(transport)) {
            S3MediaCleanupStorage storage = new S3MediaCleanupStorage(
                    client, "test-originals", "test-delivery", 3, 1000);

            assertThatThrownBy(() -> storage.purgeAssetObjects(ASSET_ID))
                    .isInstanceOfSatisfying(MediaCleanupStorageException.class, error -> {
                        assertThat(error.getReason()).isEqualTo(Reason.STORAGE_UNAVAILABLE);
                        assertThat(error.getMessage()).isEqualTo("media cleanup storage: STORAGE_UNAVAILABLE");
                        assertThat(error.getCause()).isNull();
                    });
        }
        verify(transport, times(4)).prepareRequest(any(HttpExecuteRequest.class));
    }

    @Test
    void 비어_보이더라도_필수_완료_표시가_없는_XML이면_삭제_완료로_보지_않는다() throws IOException {
        SdkHttpClient transport = mock(SdkHttpClient.class);
        ExecutableHttpRequest incompleteResponse = response("""
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"/>
                """);
        ExecutableHttpRequest versioningResponse = response(VERSIONING_ENABLED);
        when(transport.prepareRequest(any(HttpExecuteRequest.class)))
                .thenReturn(versioningResponse, incompleteResponse);

        try (S3Client client = client(transport)) {
            S3MediaCleanupStorage storage = new S3MediaCleanupStorage(
                    client, "test-originals", "test-delivery", 3, 1000);

            assertThatThrownBy(() -> storage.purgeAssetObjects(ASSET_ID))
                    .isInstanceOfSatisfying(MediaCleanupStorageException.class, error ->
                            assertThat(error.getReason()).isEqualTo(Reason.INVALID_STORAGE_RESPONSE));
        }
        verify(transport, times(2)).prepareRequest(any(HttpExecuteRequest.class));
    }

    private S3Client client(SdkHttpClient transport) {
        return S3Client.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .endpointOverride(URI.create("https://s3.test.invalid"))
                .forcePathStyle(true).httpClient(transport).build();
    }

    private ExecutableHttpRequest response(String body) throws IOException {
        return response(200, body);
    }

    private ExecutableHttpRequest response(int status, String body) throws IOException {
        ExecutableHttpRequest request = mock(ExecutableHttpRequest.class);
        when(request.call()).thenReturn(HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(status)
                        .headers(Map.of("content-type", List.of("application/xml"))).build())
                .responseBody(AbortableInputStream.create(
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
                .build());
        return request;
    }

    private String requestBody(HttpExecuteRequest request) throws IOException {
        if (request.contentStreamProvider().isEmpty()) {
            return "";
        }
        try (var stream = request.contentStreamProvider().orElseThrow().newStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
