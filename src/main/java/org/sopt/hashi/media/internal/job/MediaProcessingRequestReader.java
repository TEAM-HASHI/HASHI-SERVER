package org.sopt.hashi.media.internal.job;

import java.util.Optional;
import java.util.UUID;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaProcessingRequestReader {

    private final ImageAssetRepository imageAssetRepository;

    public MediaProcessingRequestReader(ImageAssetRepository imageAssetRepository) {
        this.imageAssetRepository = imageAssetRepository;
    }

    @Transactional(readOnly = true)
    public Optional<MediaTransformRequest> findCurrent(UUID assetId, UUID jobId) {
        return imageAssetRepository.findByPublicId(assetId)
                .filter(asset -> asset.hasCurrentProcessingJob(jobId))
                .map(this::toRequest);
    }

    private MediaTransformRequest toRequest(ImageAsset asset) {
        return new MediaTransformRequest(
                MediaTransformRequest.CURRENT_CONTRACT_VERSION,
                asset.getCurrentJobId(),
                asset.getPublicId(),
                asset.getPurpose(),
                asset.getTargetSpecVersion(),
                asset.getTargetSpecDigest(),
                asset.getOriginalObjectKey(),
                asset.getSourceVersionId(),
                asset.getSourceEtag(),
                asset.getDeclaredContentType(),
                asset.getDeclaredBytes()
        );
    }
}
