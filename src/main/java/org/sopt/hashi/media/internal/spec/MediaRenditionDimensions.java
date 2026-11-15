package org.sopt.hashi.media.internal.spec;

public record MediaRenditionDimensions(int width, int height) {

    public MediaRenditionDimensions {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("rendition dimensions must be positive");
        }
    }
}
