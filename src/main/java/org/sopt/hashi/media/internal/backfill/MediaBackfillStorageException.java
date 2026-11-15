package org.sopt.hashi.media.internal.backfill;

/** batch는 원시 AWS 예외 대신 이 고정된 원인만 보고하고 다른 항목을 계속 처리한다. */
public class MediaBackfillStorageException extends RuntimeException {

    public enum Reason {
        SOURCE_MISSING,
        SOURCE_UNREADABLE,
        SOURCE_CHANGED,
        INVALID_SOURCE,
        COPY_CONFLICT,
        STORAGE_UNAVAILABLE
    }

    private final Reason reason;

    public MediaBackfillStorageException(Reason reason) {
        super("media backfill storage: " + reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
