package org.sopt.hashi.media.internal.reconciliation;

import java.time.Instant;
import java.util.Objects;

public record MediaObjectVersion(
        MediaObjectLocation location,
        String objectKey,
        String versionId,
        Instant lastModified
) {

    public MediaObjectVersion {
        Objects.requireNonNull(location);
        if (objectKey == null || !objectKey.startsWith(location.prefix())) {
            throw new IllegalArgumentException("media object key is outside the configured prefix");
        }
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("media object version is required");
        }
        Objects.requireNonNull(lastModified);
    }

    @Override
    public String toString() {
        return "MediaObjectVersion[redacted]";
    }
}
