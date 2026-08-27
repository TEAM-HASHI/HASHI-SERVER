package org.sopt.hashi.media.internal.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MediaSpecRegistryTest {

    @Test
    void v1_manifest의_정확한_LF_bytes_digest를_사용한다() {
        MediaSpecRegistry registry = new MediaSpecRegistry(new ObjectMapper());

        assertThat(registry.find(1)).contains(new MediaSpecSnapshot(
                1,
                "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32"
        ));
        assertThat(registry.find(2)).isEmpty();
    }
}
