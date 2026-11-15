package org.sopt.hashi.media.internal.storage;

public record OriginalObjectMetadata(
        String objectKey,
        String versionId,
        String eTag,
        String contentType,
        long contentLength
) {
}
