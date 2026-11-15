package org.sopt.hashi.media.service;

import java.nio.charset.StandardCharsets;

final class MediaSourceIdentity {

    static final int MAX_VERSION_ID_BYTES = 1024;

    private MediaSourceIdentity() {
    }

    static boolean isValid(String versionId, String eTag) {
        return hasText(versionId)
                && versionId.getBytes(StandardCharsets.UTF_8).length <= MAX_VERSION_ID_BYTES
                && hasText(eTag);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
