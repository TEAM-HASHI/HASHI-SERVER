package org.sopt.hashi.media.internal.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;

@ExtendWith(MockitoExtension.class)
class MediaProcessingRequestReaderTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";

    @Mock
    private ImageAssetRepository imageAssetRepository;

    @Test
    void 현재_PROCESSING_job의_immutable_snapshot을_반환한다() {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImageAsset asset = processingAsset(assetId, jobId);
        given(imageAssetRepository.findByPublicId(assetId)).willReturn(Optional.of(asset));
        MediaProcessingRequestReader reader =
                new MediaProcessingRequestReader(imageAssetRepository);

        Optional<MediaTransformRequest> result = reader.findCurrent(assetId, jobId);

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.contractVersion()).isEqualTo(1);
            assertThat(request.jobId()).isEqualTo(jobId);
            assertThat(request.assetId()).isEqualTo(assetId);
            assertThat(request.purpose()).isEqualTo(MediaPurpose.REVIEW);
            assertThat(request.specVersion()).isEqualTo(1);
            assertThat(request.specDigest()).isEqualTo(SPEC_DIGEST);
            assertThat(request.sourceVersionId()).isEqualTo("version-1");
            assertThat(request.sourceETag()).isEqualTo("\"etag-1\"");
        });
    }

    @Test
    void event_job이_현재_job과_다르면_snapshot을_만들지_않는다() {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = processingAsset(assetId, UUID.randomUUID());
        given(imageAssetRepository.findByPublicId(assetId)).willReturn(Optional.of(asset));
        MediaProcessingRequestReader reader =
                new MediaProcessingRequestReader(imageAssetRepository);

        assertThat(reader.findCurrent(assetId, UUID.randomUUID())).isEmpty();
    }

    private ImageAsset processingAsset(UUID assetId, UUID jobId) {
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.REVIEW,
                MediaOwnerType.USER,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        return asset;
    }
}
