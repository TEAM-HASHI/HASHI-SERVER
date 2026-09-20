package org.sopt.hashi.media.internal.spec;

import java.util.Objects;
import org.sopt.hashi.media.domain.ImageRole;

public record MediaExpectedRendition(ImageRole role, int width, int height) {

    public MediaExpectedRendition {
        Objects.requireNonNull(role, "role must not be null");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("expected rendition dimensions must be positive");
        }
    }
}
