package org.sopt.hashi.media.internal.queue;

import java.util.Objects;
import java.util.UUID;

public record MediaTransformFailedResult(
        int contractVersion,
        UUID jobId,
        UUID assetId,
        int specVersion,
        String specDigest,
        String sourceVersionId,
        String sourceETag,
        MediaTransformFailureCode failureCode
) implements MediaTransformResult {

    public MediaTransformFailedResult {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(specDigest);
        Objects.requireNonNull(sourceVersionId);
        Objects.requireNonNull(sourceETag);
        Objects.requireNonNull(failureCode);
    }
}
