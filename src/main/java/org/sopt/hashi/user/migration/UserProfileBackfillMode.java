package org.sopt.hashi.user.migration;

public enum UserProfileBackfillMode {

    DRY_RUN,
    PREPARE,
    ATTACH;

    boolean usesCheckpoint() {
        return this != DRY_RUN;
    }
}
