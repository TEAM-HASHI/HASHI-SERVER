package org.sopt.hashi.magazine.migration;

import java.util.Objects;

/** 전환 대상 슬롯의 최소 입력이며 콘텐츠 ID와 경로를 출력하지 않는다. */
record MagazineMediaBackfillCandidate(
        MagazineMediaBackfillTarget target,
        long magazineId,
        String legacyKey
) {

    MagazineMediaBackfillCandidate {
        Objects.requireNonNull(target, "target is required");
        if (magazineId < 1 || legacyKey == null) {
            throw new IllegalArgumentException("invalid magazine media backfill candidate");
        }
    }

    boolean hasUsableLegacyKey() {
        return !legacyKey.isBlank();
    }

    @Override
    public String toString() {
        return "MagazineMediaBackfillCandidate[redacted]";
    }
}
