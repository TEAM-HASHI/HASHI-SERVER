package org.sopt.hashi.user.migration;

/** 프로필 슬롯 조사에 필요한 최소 입력만 보관하며 개인정보와 경로를 출력하지 않는다. */
record UserProfileBackfillCandidate(long userId, String legacyKey) {

    UserProfileBackfillCandidate {
        if (userId < 1 || legacyKey == null) {
            throw new IllegalArgumentException("invalid user profile backfill candidate");
        }
    }

    boolean hasUsableLegacyKey() {
        return !legacyKey.isBlank();
    }

    @Override
    public String toString() {
        return "UserProfileBackfillCandidate[redacted]";
    }
}
