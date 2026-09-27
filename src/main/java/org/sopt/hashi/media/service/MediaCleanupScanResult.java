package org.sopt.hashi.media.service;

public record MediaCleanupScanResult(Status status, int attemptedCount, int batchCount, int failedCount) {

    public enum Status {
        COMPLETED,
        PARTIAL_FAILURE,
        RETRY_PENDING,
        WORK_LIMIT_REACHED,
        TIME_LIMIT_REACHED,
        DISABLED,
        ALREADY_RUNNING,
        INTERRUPTED,
        FAILED
    }
}
