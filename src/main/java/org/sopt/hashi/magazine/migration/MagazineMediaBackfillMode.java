package org.sopt.hashi.magazine.migration;

public enum MagazineMediaBackfillMode {

    DRY_RUN,
    PREPARE,
    ATTACH;

    boolean usesCheckpoint() {
        return this != DRY_RUN;
    }
}
