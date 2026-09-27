package org.sopt.hashi.media.internal.queue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MediaTransformSucceededResult(
        int contractVersion,
        UUID jobId,
        UUID assetId,
        int specVersion,
        String specDigest,
        String sourceVersionId,
        String sourceETag,
        MediaVerifiedSource verifiedSource,
        List<MediaRenditionResult> renditions
) implements MediaTransformResult {

    public MediaTransformSucceededResult {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(specDigest);
        Objects.requireNonNull(sourceVersionId);
        Objects.requireNonNull(sourceETag);
        Objects.requireNonNull(verifiedSource);
        renditions = List.copyOf(Objects.requireNonNull(renditions));
    }
}
