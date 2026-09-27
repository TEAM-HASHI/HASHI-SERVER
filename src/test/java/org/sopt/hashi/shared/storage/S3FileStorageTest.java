package org.sopt.hashi.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class S3FileStorageTest {

    // Real SDK signing with fake static credentials: no credential discovery or S3 calls.
    private final S3Presigner presigner = spy(S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access-key", "test-secret-key")))
            .build());

    @AfterEach
    void closePresigner() {
        presigner.close();
    }

    @ParameterizedTest
    @CsvSource({"image/jpeg, 1024, 300", "image/png, 2048, 73", "image/webp, 4096, 600"})
    void 실제_SDK로_PUT과_파일_헤더와_설정된_만료시간을_서명한다(
            String contentType, long contentLength, int expirationSeconds) {
        S3FileStorage storage = storage(Duration.ofSeconds(expirationSeconds));
        String fileKey = "uploads/reviews/2026/09/14/test-image.jpg";

        PresignedUploadInfo upload = storage.createPresignedUploadUrl(fileKey, contentType, contentLength);

        URI uploadUri = URI.create(upload.uploadUrl());
        Map<String, String> query = queryParameters(uploadUri);
        assertThat(upload.uploadMethod()).isEqualTo("PUT");
        assertThat(upload.fileKey()).isEqualTo(fileKey);
        assertThat(upload.fileUrl()).isEqualTo("https://cdn.example.com/" + fileKey);
        assertThat(upload.expiresInSeconds()).isEqualTo(expirationSeconds);
        assertThat(uploadUri.getScheme()).isEqualTo("https");
        assertThat(uploadUri.getHost()).isEqualTo("hashi-test-uploads.s3.ap-northeast-2.amazonaws.com");
        assertThat(uploadUri.getRawPath()).isEqualTo("/" + fileKey);
        assertThat(query).containsEntry("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .containsEntry("X-Amz-Expires", Integer.toString(expirationSeconds));
        assertThat(query.get("X-Amz-Signature")).matches("[0-9a-f]{64}");
        assertThat(query.get("X-Amz-SignedHeaders").split(";"))
                .containsExactlyInAnyOrder("content-length", "content-type", "host");

        ArgumentCaptor<PutObjectPresignRequest> request = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(request.capture());
        assertThat(request.getValue().signatureDuration()).isEqualTo(Duration.ofSeconds(expirationSeconds));
        assertThat(request.getValue().putObjectRequest().bucket()).isEqualTo("hashi-test-uploads");
        assertThat(request.getValue().putObjectRequest().key()).isEqualTo(fileKey);
        assertThat(request.getValue().putObjectRequest().contentType()).isEqualTo(contentType);
        assertThat(request.getValue().putObjectRequest().contentLength()).isEqualTo(contentLength);
    }

    @Test
    void 업로드_URL은_키의_특수문자를_인코딩하고_응답_키는_유지한다() {
        String fileKey = "uploads/reviews/한 글+100%.jpg";

        PresignedUploadInfo upload = storage(Duration.ofMinutes(5))
                .createPresignedUploadUrl(fileKey, "image/jpeg", 1024L);

        URI uploadUri = URI.create(upload.uploadUrl());
        assertThat(uploadUri.getRawPath())
                .isEqualTo("/uploads/reviews/%ED%95%9C%20%EA%B8%80%2B100%25.jpg");
        assertThat(uploadUri.getPath()).isEqualTo("/" + fileKey);
        assertThat(upload.fileKey()).isEqualTo(fileKey);
    }

    @Test
    void 파일_URL은_CloudFront_도메인과_정리된_키로_만든다() {
        S3FileStorage storage = storage(Duration.ofMinutes(5));

        assertThat(storage.resolveFileUrl("  ///uploads/reviews/test.jpg  "))
                .isEqualTo("https://cdn.example.com/uploads/reviews/test.jpg");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void 빈_키는_파일_URL을_만들지_않는다(String fileKey) {
        assertThat(storage(Duration.ofMinutes(5)).resolveFileUrl(fileKey)).isNull();
    }

    private S3FileStorage storage(Duration expiration) {
        return new S3FileStorage(presigner, new StorageProperties(
                "ap-northeast-2",
                "hashi-test-uploads",
                "https://cdn.example.com//",
                expiration,
                DataSize.ofMegabytes(5)
        ));
    }

    private Map<String, String> queryParameters(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        parameter -> URLDecoder.decode(parameter[0], StandardCharsets.UTF_8),
                        parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)
                ));
    }
}
