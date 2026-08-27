package org.sopt.hashi.media.internal.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaProcessingJobIdFactoryTest {

    private static final UUID ASSET_ID =
            UUID.fromString("a3af06f1-4ef2-46f8-a489-2347fb840447");

    private final MediaProcessingJobIdFactory factory = new MediaProcessingJobIdFactory();

    @Test
    void 같은_asset_source_spec은_항상_같은_UUIDv5를_생성한다() {
        UUID first = factory.create(ASSET_ID, "3Lg-source-version", 1);
        UUID second = factory.create(ASSET_ID, "3Lg-source-version", 1);

        assertThat(first).isEqualTo(second);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }

    @Test
    void source나_spec이_달라지면_job_ID도_달라진다() {
        UUID baseline = factory.create(ASSET_ID, "version-1", 1);

        assertThat(factory.create(ASSET_ID, "version-2", 1)).isNotEqualTo(baseline);
        assertThat(factory.create(ASSET_ID, "version-1", 2)).isNotEqualTo(baseline);
    }

    @Test
    void RFC_UUIDv5_표준_vector를_만족한다() {
        UUID dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        UUID result = MediaProcessingJobIdFactory.uuidV5(dnsNamespace, "www.widgets.com");

        assertThat(result).isEqualTo(UUID.fromString("21f7f8de-8051-5b89-8680-0195ef798b6a"));
    }
}
