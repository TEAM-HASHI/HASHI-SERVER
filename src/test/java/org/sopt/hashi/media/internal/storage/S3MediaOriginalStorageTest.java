package org.sopt.hashi.media.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3MediaOriginalStorageTest {

    @Test
    void Content_Type과_Length와_조건부_PUT_헤더를_서명한다() {
        MediaOriginalStorageProperties properties = new MediaOriginalStorageProperties(
                "ap-northeast-2",
                "hashi-test-originals",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(5),
                10
        );
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build();
        S3MediaOriginalStorage storage = new S3MediaOriginalStorage(
                presigner,
                mock(S3Client.class),
                properties
        );

        PresignedOriginalUpload upload = storage.createPresignedUpload(
                "media/originals/asset-id/original",
                "image/jpeg",
                1024L
        );

        assertThat(upload.requiredHeaders()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"));
        assertThat(upload.expectedContentLength()).isEqualTo(1024L);
        assertThat(upload.uploadMethod()).isEqualTo("PUT");
        assertThat(upload.uploadUrl()).contains("X-Amz-SignedHeaders=");

        storage.close();
    }
}
