package org.sopt.hashi.media.service;

public enum MediaCleanupOutcome {
    WOULD_PURGE,
    PURGED,
    ALREADY_PURGED,
    SKIPPED,
    INCOMPLETE
}
