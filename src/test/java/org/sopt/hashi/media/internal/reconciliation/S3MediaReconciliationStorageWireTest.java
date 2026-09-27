package org.sopt.hashi.media.internal.reconciliation;

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
import java.time.Instant;
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
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class S3MediaReconciliationStorageWireTest {

    private static final UUID ASSET_ID = UUID.fromString("427e4107-24c3-4d4a-a1d8-6bd78785a7fb");
    private static final String KEY = "media/originals/" + ASSET_ID + "/original";
    private static final String VERSION = "v+/?=&";
    private static final String VERSIONING_ENABLED = """
            <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Status>Enabled</Status>
            </VersioningConfiguration>
            """;

    @Test
    void 실제_SDK가_목록의_exact_key와_version을_DELETE_query에_전달한다() throws Exception {
        String page = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Name>test-originals</Name><Prefix>media/originals/</Prefix><IsTruncated>false</IsTruncated>
                    <Version><Key>%s</Key><VersionId>v+/?=&amp;</VersionId><IsLatest>false</IsLatest>
                    <LastModified>2026-08-01T00:00:00.000Z</LastModified></Version>
                </ListVersionsResult>
        """.formatted(KEY);
        SdkHttpClient transport = mock(SdkHttpClient.class);
        ExecutableHttpRequest listVersioningResponse = response(200, VERSIONING_ENABLED);
        ExecutableHttpRequest listResponse = response(200, page);
        ExecutableHttpRequest deleteVersioningResponse = response(200, VERSIONING_ENABLED);
        ExecutableHttpRequest deleteResponse = response(204, "");
        when(transport.prepareRequest(any(HttpExecuteRequest.class)))
                .thenReturn(listVersioningResponse, listResponse, deleteVersioningResponse, deleteResponse);

        try (S3Client client = client(transport)) {
            S3MediaReconciliationStorage storage =
                    new S3MediaReconciliationStorage(client, "test-originals", "test-delivery");
            MediaObjectVersion object = storage.listObjectVersions(
                    MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100)
                    .objects().getFirst();

            assertThat(object).isEqualTo(new MediaObjectVersion(
                    MediaObjectLocation.ORIGINAL, KEY, VERSION, Instant.parse("2026-08-01T00:00:00Z")));
            storage.deleteObjectVersion(object);
        }

        ArgumentCaptor<HttpExecuteRequest> requests = ArgumentCaptor.forClass(HttpExecuteRequest.class);
        verify(transport, times(4)).prepareRequest(requests.capture());
        HttpExecuteRequest list = requests.getAllValues().get(1);
        HttpExecuteRequest delete = requests.getAllValues().getLast();
        assertThat(requests.getAllValues().getFirst().httpRequest().rawQueryParameters())
                .containsKey("versioning");
        assertThat(requests.getAllValues().get(2).httpRequest().rawQueryParameters())
                .containsKey("versioning");
        assertThat(list.httpRequest().method()).isEqualTo(SdkHttpMethod.GET);
        assertThat(list.httpRequest().encodedPath()).isEqualTo("/test-originals");
        assertThat(list.httpRequest().rawQueryParameters()).containsKeys("versions", "prefix", "max-keys");
        assertThat(list.httpRequest().rawQueryParameters()).containsEntry("encoding-type", List.of("url"));
        assertThat(delete.httpRequest().method()).isEqualTo(SdkHttpMethod.DELETE);
        assertThat(delete.httpRequest().encodedPath()).endsWith("/media/originals/" + ASSET_ID + "/original");
        assertThat(delete.httpRequest().rawQueryParameters())
                .containsEntry("versionId", List.of(VERSION));
        assertThat(delete.httpRequest().firstMatchingHeader("x-amz-bypass-governance-retention")).isEmpty();
        assertThat(delete.httpRequest().firstMatchingHeader("x-amz-mfa")).isEmpty();
    }

    @Test
    void 인코딩한_파일명과_다음_page_marker를_실제_SDK가_복원한다() throws Exception {
        String unknownKey = "media/originals/unknown" + (char) 1;
        String page = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Name>test-originals</Name><Prefix>media%%2Foriginals%%2F</Prefix>
                    <EncodingType>url</EncodingType><IsTruncated>true</IsTruncated>
                    <NextKeyMarker>media%%2Foriginals%%2Funknown%%01</NextKeyMarker>
                    <NextVersionIdMarker>v+/?=&amp;</NextVersionIdMarker>
                    <Version><Key>media%%2Foriginals%%2F%s%%2Foriginal</Key><VersionId>v+/?=&amp;</VersionId>
                    <LastModified>2026-08-01T00:00:00.000Z</LastModified></Version>
                    <Version><Key>media%%2Foriginals%%2Funknown%%01</Key><VersionId>v+/?=&amp;</VersionId>
                    <LastModified>2026-08-01T00:00:00.000Z</LastModified></Version>
                </ListVersionsResult>
                """.formatted(ASSET_ID);
        String lastPage = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Name>test-originals</Name><Prefix>media%2Foriginals%2F</Prefix>
                    <EncodingType>url</EncodingType><IsTruncated>false</IsTruncated>
                </ListVersionsResult>
                """;
        SdkHttpClient transport = mock(SdkHttpClient.class);
        ExecutableHttpRequest firstVersioningResponse = response(200, VERSIONING_ENABLED);
        ExecutableHttpRequest firstPageResponse = response(200, page);
        ExecutableHttpRequest nextVersioningResponse = response(200, VERSIONING_ENABLED);
        ExecutableHttpRequest lastPageResponse = response(200, lastPage);
        when(transport.prepareRequest(any(HttpExecuteRequest.class))).thenReturn(
                firstVersioningResponse, firstPageResponse, nextVersioningResponse, lastPageResponse);

        try (S3Client client = client(transport)) {
            S3MediaReconciliationStorage storage =
                    new S3MediaReconciliationStorage(client, "test-originals", "test-delivery");
            MediaObjectVersionPage first = storage.listObjectVersions(
                    MediaObjectLocation.ORIGINAL, MediaObjectVersionCursor.initial(), 100);

            assertThat(first.objects()).extracting(MediaObjectVersion::objectKey)
                    .containsExactly(KEY, unknownKey);
            assertThat(first.nextCursor()).isEqualTo(new MediaObjectVersionCursor(unknownKey, VERSION));
            MediaObjectVersionPage last = storage.listObjectVersions(
                    MediaObjectLocation.ORIGINAL, first.nextCursor(), 100);
            assertThat(last.objects()).isEmpty();
            assertThat(last.nextCursor()).isNull();
        }

        ArgumentCaptor<HttpExecuteRequest> requests = ArgumentCaptor.forClass(HttpExecuteRequest.class);
        verify(transport, times(4)).prepareRequest(requests.capture());
        HttpExecuteRequest firstList = requests.getAllValues().get(1);
        HttpExecuteRequest nextList = requests.getAllValues().getLast();
        assertThat(firstList.httpRequest().rawQueryParameters())
                .containsEntry("encoding-type", List.of("url"));
        assertThat(nextList.httpRequest().rawQueryParameters())
                .containsEntry("encoding-type", List.of("url"))
                .containsEntry("key-marker", List.of(unknownKey))
                .containsEntry("version-id-marker", List.of(VERSION));
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.httpRequest().method()).isEqualTo(SdkHttpMethod.GET));
    }

    private S3Client client(SdkHttpClient transport) {
        return S3Client.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .endpointOverride(URI.create("https://s3.test.invalid"))
                .forcePathStyle(true).httpClient(transport).build();
    }

    private ExecutableHttpRequest response(int status, String body) throws IOException {
        ExecutableHttpRequest request = mock(ExecutableHttpRequest.class);
        HttpExecuteResponse.Builder response = HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(status)
                        .headers(Map.of("content-type", List.of("application/xml"))).build());
        if (!body.isEmpty()) {
            response.responseBody(AbortableInputStream.create(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        }
        when(request.call()).thenReturn(response.build());
        return request;
    }
}
