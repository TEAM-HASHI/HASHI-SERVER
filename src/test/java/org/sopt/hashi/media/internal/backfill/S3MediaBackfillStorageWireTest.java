package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class S3MediaBackfillStorageWireTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final String IDENTITY = "a".repeat(64);

    @Test
    void SDK가_실제_HTTP_요청에도_annotation_제외_헤더를_전달한다() throws IOException {
        SdkHttpClient transport = mock(SdkHttpClient.class);
        ExecutableHttpRequest listResponse = response(Map.of("content-type", List.of("application/xml")), """
                        <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                            <IsTruncated>false</IsTruncated>
                        </ListVersionsResult>
                        """);
        ExecutableHttpRequest copyResponse = response(Map.of("content-type", List.of("application/xml"),
                        "x-amz-version-id", List.of("copy-v1")), """
                        <CopyObjectResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                            <LastModified>2026-08-01T00:00:00Z</LastModified>
                            <ETag>"copy-etag"</ETag>
                        </CopyObjectResult>
                        """);
        ExecutableHttpRequest headResponse = response(Map.of("content-type", List.of("image/jpeg"),
                        "content-length", List.of("1024"),
                        "etag", List.of("\"copy-etag\""),
                        "x-amz-version-id", List.of("copy-v1"),
                        "x-amz-meta-backfill-identity", List.of(IDENTITY)), "");
        when(transport.prepareRequest(any(HttpExecuteRequest.class)))
                .thenReturn(listResponse, copyResponse, headResponse);

        // HTTP 전송만 대체한다. 실제 SDK의 marshalling과 signing을 거치며 AWS에는 접속하지 않는다.
        try (S3Client client = S3Client.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .endpointOverride(URI.create("https://s3.test.invalid"))
                .forcePathStyle(true)
                .httpClient(transport)
                .build()) {
            S3MediaBackfillStorage storage = new S3MediaBackfillStorage(client, "test-delivery", "test-originals");
            LegacyImageSource source = new LegacyImageSource(
                    "test-delivery", "legacy/photo.jpg", "source-v1", "\"source-etag\"", "image/jpeg", 1024);

            BackfillOriginalCopy copy = storage.findOrCopyOriginal(ASSET_ID, IDENTITY, source);

            assertThat(copy.versionId()).isEqualTo("copy-v1");
        }

        ArgumentCaptor<HttpExecuteRequest> requests = ArgumentCaptor.forClass(HttpExecuteRequest.class);
        verify(transport, times(3)).prepareRequest(requests.capture());
        SdkHttpRequest copyRequest = requests.getAllValues().get(1).httpRequest();
        assertThat(copyRequest.method()).isEqualTo(SdkHttpMethod.PUT);
        assertThat(copyRequest.firstMatchingHeader("x-amz-object-annotation-directive")).contains("EXCLUDE");
        assertThat(copyRequest.firstMatchingHeader("x-amz-metadata-directive")).contains("REPLACE");
        assertThat(copyRequest.firstMatchingHeader("x-amz-tagging-directive")).contains("REPLACE");
        assertThat(copyRequest.firstMatchingHeader("x-amz-copy-source-if-match")).contains("\"source-etag\"");
    }

    private ExecutableHttpRequest response(Map<String, List<String>> headers, String body) throws IOException {
        ExecutableHttpRequest request = mock(ExecutableHttpRequest.class);
        when(request.call()).thenReturn(HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(200).headers(headers).build())
                .responseBody(AbortableInputStream.create(
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
                .build());
        return request;
    }
}
