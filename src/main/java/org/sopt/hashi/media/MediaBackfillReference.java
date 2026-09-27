package org.sopt.hashi.media;

import java.util.Objects;

/** 소유 runner가 전달하는 일시적 입력. 원시 association ID와 key는 저장하거나 출력하지 않는다. */
public record MediaBackfillReference(MediaBackfillTarget target, long associationId, String legacyKey) {

    public MediaBackfillReference {
        Objects.requireNonNull(target, "backfill target is required");
        if (associationId < 1 || legacyKey == null || legacyKey.isBlank()) {
            throw new IllegalArgumentException("invalid backfill reference");
        }
    }

    @Override
    public String toString() {
        return "MediaBackfillReference[redacted]";
    }
}
