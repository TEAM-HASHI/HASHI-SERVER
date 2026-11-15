package org.sopt.hashi.media.internal.queue;

import java.util.UUID;

public sealed interface MediaTransformResult
        permits MediaTransformSucceededResult, MediaTransformFailedResult {

    int contractVersion();

    UUID jobId();

    UUID assetId();

    int specVersion();

    String specDigest();

    String sourceVersionId();

    String sourceETag();
}
