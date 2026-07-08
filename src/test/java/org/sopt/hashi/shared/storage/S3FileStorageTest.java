package org.sopt.hashi.shared.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class S3FileStorageTest {

    @Test
    void cloudfrontDomain이_없으면_fileUrl을_생성하지_않는다() {
        StorageProperties storageProperties = new StorageProperties(
                "ap-northeast-2",
                "hashi-dev-uploads",
                "",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(5)
        );
        S3FileStorage fileStorage = new S3FileStorage(null, storageProperties);

        assertThatThrownBy(() -> fileStorage.createPresignedUploadUrl(
                "uploads/reviews/2026/07/09/test.jpg",
                "image/jpeg",
                1024L
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("CloudFront domain must be configured to create fileUrl.");
    }
}
