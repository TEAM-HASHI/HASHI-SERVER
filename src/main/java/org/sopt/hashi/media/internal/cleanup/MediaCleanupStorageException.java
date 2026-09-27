package org.sopt.hashi.media.internal.cleanup;

import java.util.Objects;

public class MediaCleanupStorageException extends RuntimeException {

    private final Reason reason;

    public MediaCleanupStorageException(Reason reason) {
        // SDK 예외와 응답에는 bucket, key, version 등이 포함될 수 있어 원문 cause를 전달하지 않는다.
        super("media cleanup storage: " + Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        STORAGE_UNAVAILABLE,
        INVALID_STORAGE_RESPONSE,
        PARTIAL_DELETE,
        INTERRUPTED
    }
}
