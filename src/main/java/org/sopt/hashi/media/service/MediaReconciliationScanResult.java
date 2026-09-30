package org.sopt.hashi.media.service;

public record MediaReconciliationScanResult(
        Status status,
        int pages,
        int inspected,
        int wouldDelete,
        int deleted,
        int protectedObjects,
        int unknown,
        int failed
) {

    public enum Status {
        COMPLETED,
        PARTIAL_FAILURE,
        PAGE_LIMIT_REACHED,
        TIME_LIMIT_REACHED,
        INTERRUPTED,
        DISABLED,
        ALREADY_RUNNING
    }
}
