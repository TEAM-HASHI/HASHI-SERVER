package org.sopt.hashi.media.internal.storage;

public class MediaStorageUnavailableException extends RuntimeException {

    public MediaStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public MediaStorageUnavailableException(String message) {
        super(message);
    }
}
