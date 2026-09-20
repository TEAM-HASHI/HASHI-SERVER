package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaProcessingJobIdTest {

    private static final UUID ASSET_ID = UUID.fromString("a3af06f1-4ef2-46f8-a489-2347fb840447");

    @Test
    void 같은_asset_source_version_spec은_같은_UUIDv5를_만든다() {
        UUID first = MediaProcessingJobId.from(ASSET_ID, "version-1", 1);
        UUID second = MediaProcessingJobId.from(ASSET_ID, "version-1", 1);

        assertThat(first).isEqualTo(UUID.fromString("ebb9b9d8-c427-564b-a70e-0fd4e1925e5a"));
        assertThat(second).isEqualTo(first);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }

    @Test
    void job_식별자_구성요소가_달라지면_다른_ID를_만든다() {
        UUID baseline = MediaProcessingJobId.from(ASSET_ID, "version-1", 1);

        assertThat(MediaProcessingJobId.from(UUID.randomUUID(), "version-1", 1))
                .isNotEqualTo(baseline);
        assertThat(MediaProcessingJobId.from(ASSET_ID, "version-2", 1))
                .isNotEqualTo(baseline);
        assertThat(MediaProcessingJobId.from(ASSET_ID, "version-1", 2))
                .isNotEqualTo(baseline);
    }
}
