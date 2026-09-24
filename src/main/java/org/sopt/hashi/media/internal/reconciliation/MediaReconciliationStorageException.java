package org.sopt.hashi.media.internal.reconciliation;

import java.util.Objects;

public class MediaReconciliationStorageException extends RuntimeException {

    private final Reason reason;

    public MediaReconciliationStorageException(Reason reason) {
        // SDK 원문에는 bucket, key, version이 포함될 수 있으므로 cause와 원문을 노출하지 않는다.
        super("media reconciliation storage: " + Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        STORAGE_UNAVAILABLE,
        VERSIONING_NOT_ENABLED,
        INVALID_STORAGE_RESPONSE,
        DELETE_FAILED,
        INTERRUPTED
    }
}
