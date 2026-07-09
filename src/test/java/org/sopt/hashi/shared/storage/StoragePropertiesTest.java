package org.sopt.hashi.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class StoragePropertiesTest {

    @Test
    void cloudfrontDomain을_https_URL로_정규화한다() {
        StorageProperties storageProperties = new StorageProperties(
                "ap-northeast-2",
                "hashi-dev-uploads",
                "https://d111111abcdef8.cloudfront.net//",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(5)
        );

        assertThat(storageProperties.cloudfrontDomain())
                .isEqualTo("https://d111111abcdef8.cloudfront.net");
    }

    @Test
    void cloudfrontDomain이_없으면_설정_생성에_실패한다() {
        assertThatThrownBy(() -> new StorageProperties(
                "ap-northeast-2",
                "hashi-dev-uploads",
                "",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CloudFront domain must be configured.");
    }

    @Test
    void cloudfrontDomain이_http이면_설정_생성에_실패한다() {
        assertThatThrownBy(() -> new StorageProperties(
                "ap-northeast-2",
                "hashi-dev-uploads",
                "http://d111111abcdef8.cloudfront.net",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CloudFront domain must use HTTPS.");
    }
}
