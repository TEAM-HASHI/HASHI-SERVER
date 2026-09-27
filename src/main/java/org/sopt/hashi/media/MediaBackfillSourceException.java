package org.sopt.hashi.media;

import java.util.Objects;

/** runner가 항목별 실패를 분류하는 공개 경계. AWS 예외와 원시 경로를 cause에 담지 않는다. */
public class MediaBackfillSourceException extends RuntimeException {

    private final Reason reason;

    public MediaBackfillSourceException(Reason reason) {
        super("media backfill source: " + Objects.requireNonNull(reason, "reason is required").name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        SOURCE_MISSING,
        SOURCE_UNREADABLE,
        SOURCE_CHANGED,
        INVALID_SOURCE,
        COPY_CONFLICT,
        STORAGE_UNAVAILABLE
    }
}
