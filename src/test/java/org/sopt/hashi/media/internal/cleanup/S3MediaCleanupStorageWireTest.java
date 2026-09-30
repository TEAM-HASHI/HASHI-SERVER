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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException.Reason;
import org.w3c.dom.NodeList;
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
    private static final String DELETED = """
            <DeleteResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"/>
            """;
    private static final String VERSIONING_ENABLED = """
            <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Status>Enabled</Status>
            </VersioningConfiguration>
            """;

    @Test
    void 실제_SDK가_version과_marker_null_version을_삭제_XML에_그대로_전달한다() throws Exception {
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
        List<String> bodies = new ArrayList<>();
        List<ExecutableHttpRequest> responses = List.of(
                response(VERSIONING_ENABLED), response(originalPage),
                response(VERSIONING_ENABLED), response(DELETED),
                response(VERSIONING_ENABLED), response(EMPTY),
                response(VERSIONING_ENABLED), response(deliveryPage),
                response(VERSIONING_ENABLED), response(DELETED),
                response(VERSIONING_ENABLED), response(EMPTY));
        when(transport.prepareRequest(any(HttpExecuteRequest.class))).thenAnswer(invocation -> {
            HttpExecuteRequest request = invocation.getArgument(0);
            bodies.add(requestBody(request));
            return responses.get(bodies.size() - 1);
        });

        // 실제 SDK 직렬화·서명·XML 역직렬화를 사용하되 HTTP 전송은 모킹하여 AWS에 접속하지 않는다.
        try (S3Client client = client(transport)) {
            S3MediaCleanupStorage storage = new S3MediaCleanupStorage(
                    client, "test-originals", "test-delivery", 3, 1000);

            assertThat(storage.purgeAssetObjects(ASSET_ID)).isEqualTo(new MediaObjectPurgeResult(true, 3));
        }

        ArgumentCaptor<HttpExecuteRequest> captured = ArgumentCaptor.forClass(HttpExecuteRequest.class);
        verify(transport, times(12)).prepareRequest(captured.capture());
        HttpExecuteRequest originalDelete = captured.getAllValues().get(3);
        HttpExecuteRequest deliveryDelete = captured.getAllValues().get(9);
        assertThat(originalDelete.httpRequest().method()).isEqualTo(SdkHttpMethod.POST);
        assertThat(originalDelete.httpRequest().encodedPath()).isEqualTo("/test-originals");
        assertThat(originalDelete.httpRequest().rawQueryParameters()).containsKey("delete");
        assertThat(deliveryDelete.httpRequest().encodedPath()).isEqualTo("/test-delivery");
        assertThat(xmlValues(bodies.get(3), "Key")).containsExactly(ORIGINAL_KEY, ORIGINAL_KEY);
        assertThat(xmlValues(bodies.get(3), "VersionId")).containsExactly("v+/?=&", "marker-v1");
        assertThat(xmlValues(bodies.get(3), "Quiet")).containsExactly("true");
        assertThat(xmlValues(bodies.get(9), "Key")).containsExactly(DELIVERY_KEY);
        assertThat(xmlValues(bodies.get(9), "VersionId")).containsExactly("null");
        assertThat(captured.getAllValues()).allSatisfy(request -> {
            assertThat(request.httpRequest().rawQueryParameters())
                    .doesNotContainKeys("key-marker", "version-id-marker");
            assertThat(request.httpRequest().firstMatchingHeader("x-amz-bypass-governance-retention")).isEmpty();
            assertThat(request.httpRequest().firstMatchingHeader("x-amz-mfa")).isEmpty();
        });
    }

    @Test
    void SDK가_역직렬화한_HTTP_200_일부_실패도_완료로_처리하지_않는다() throws IOException {
        SdkHttpClient transport = mock(SdkHttpClient.class);
        String page = """
                <ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <IsTruncated>false</IsTruncated>
                    <Version><Key>%s</Key><VersionId>v1</VersionId></Version>
                </ListVersionsResult>
                """.formatted(ORIGINAL_KEY);
        String failure = """
                <DeleteResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Error><Key>%s</Key><VersionId>v1</VersionId><Code>AccessDenied</Code>
                    <Message>private-provider-message</Message></Error>
                </DeleteResult>
                """.formatted(ORIGINAL_KEY);
        ExecutableHttpRequest pageResponse = response(page);
        ExecutableHttpRequest failureResponse = response(failure);
        ExecutableHttpRequest listVersioningResponse = response(VERSIONING_ENABLED);
        ExecutableHttpRequest deleteVersioningResponse = response(VERSIONING_ENABLED);
        when(transport.prepareRequest(any(HttpExecuteRequest.class)))
                .thenReturn(listVersioningResponse, pageResponse, deleteVersioningResponse, failureResponse);

        try (S3Client client = client(transport)) {
            S3MediaCleanupStorage storage = new S3MediaCleanupStorage(
                    client, "test-originals", "test-delivery", 3, 1000);

            assertThatThrownBy(() -> storage.purgeAssetObjects(ASSET_ID))
                    .isInstanceOfSatisfying(MediaCleanupStorageException.class, error -> {
                        assertThat(error.getReason()).isEqualTo(Reason.PARTIAL_DELETE);
                        assertThat(error.getMessage()).isEqualTo("media cleanup storage: PARTIAL_DELETE");
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
        ExecutableHttpRequest request = mock(ExecutableHttpRequest.class);
        when(request.call()).thenReturn(HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(200)
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

    private List<String> xmlValues(String xml, String name) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList elements = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagNameNS("*", name);
        List<String> values = new ArrayList<>();
        for (int index = 0; index < elements.getLength(); index++) {
            values.add(elements.item(index).getTextContent());
        }
        return values;
    }
}
