package org.sopt.hashi.media.internal.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class MediaOriginalStoragePropertiesTest {

    @Test
    void worker_limit보다_큰_원본_설정은_기동을_거부한다() {
        assertThatThrownBy(() -> new MediaOriginalStorageProperties(
                "ap-northeast-2",
                "hashi-test-originals",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(6),
                10
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("media max file size must be between 1 byte and 5MB");
    }
}
