package org.sopt.hashi.media.internal.reconciliation;

public record MediaObjectVersionCursor(String keyMarker, String versionIdMarker) {

    public MediaObjectVersionCursor {
        if (keyMarker == null && versionIdMarker != null) {
            throw new IllegalArgumentException("version marker requires a key marker");
        }
        if (keyMarker != null && keyMarker.isBlank()) {
            throw new IllegalArgumentException("key marker must not be blank");
        }
        if (versionIdMarker != null && versionIdMarker.isBlank()) {
            throw new IllegalArgumentException("version marker must not be blank");
        }
    }

    public static MediaObjectVersionCursor initial() {
        return new MediaObjectVersionCursor(null, null);
    }
}
