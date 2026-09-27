package org.sopt.hashi.media.internal.queue;

import java.util.Objects;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;

public record MediaRenditionResult(
        ImageRole role,
        ImageFormat format,
        int width,
        int height,
        long byteSize,
        String objectKey
) {

    public MediaRenditionResult {
        Objects.requireNonNull(role);
        Objects.requireNonNull(format);
        Objects.requireNonNull(objectKey);
    }
}
