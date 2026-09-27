package org.sopt.hashi.media.internal.queue;

import java.util.Objects;

public record MediaVerifiedSource(
        String mimeType,
        long byteSize,
        int width,
        int height,
        String checksumSha256
) {

    public MediaVerifiedSource {
        Objects.requireNonNull(mimeType);
        Objects.requireNonNull(checksumSha256);
    }
}
