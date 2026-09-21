package org.sopt.hashi.media.service;

import java.util.Objects;
import java.util.UUID;

public record MediaPurgeWork(long assetId, UUID publicId, UUID purgeToken) {

    public MediaPurgeWork {
        if (assetId < 1) {
            throw new IllegalArgumentException("asset id must be positive");
        }
        Objects.requireNonNull(publicId);
        Objects.requireNonNull(purgeToken);
    }

    @Override
    public String toString() {
        return "MediaPurgeWork[redacted]";
    }
}
