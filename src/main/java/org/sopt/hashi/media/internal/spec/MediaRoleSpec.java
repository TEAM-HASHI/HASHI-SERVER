package org.sopt.hashi.media.internal.spec;

import java.util.Comparator;
import java.util.List;

public record MediaRoleSpec(
        int aspectRatioWidth,
        int aspectRatioHeight,
        int defaultWidth,
        int minimumFallbackWidth,
        List<MediaRenditionDimensions> candidates
) {

    public MediaRoleSpec {
        if (aspectRatioWidth < 1 || aspectRatioHeight < 1
                || defaultWidth < 1 || minimumFallbackWidth < 1) {
            throw new IllegalArgumentException("media role dimensions must be positive");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("media role candidates must not be empty");
        }
        candidates = candidates.stream()
                .sorted(Comparator.comparingInt(MediaRenditionDimensions::width))
                .toList();
        if (candidates.stream().distinct().count() != candidates.size()) {
            throw new IllegalArgumentException("media role candidates must be unique");
        }
        if (candidates.stream().map(MediaRenditionDimensions::width).distinct().count()
                != candidates.size()) {
            throw new IllegalArgumentException("media role candidate widths must be unique");
        }
        if (candidates.stream().noneMatch(candidate -> candidate.width() == defaultWidth)) {
            throw new IllegalArgumentException("media role default width must be a candidate");
        }
    }

    public List<MediaRenditionDimensions> selectFor(int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1) {
            throw new IllegalArgumentException("source dimensions must be positive");
        }
        List<MediaRenditionDimensions> standard = candidates.stream()
                .filter(candidate -> candidate.width() <= sourceWidth
                        && candidate.height() <= sourceHeight)
                .toList();
        if (!standard.isEmpty()) {
            return standard;
        }

        for (int width = sourceWidth; width >= minimumFallbackWidth; width--) {
            int height = roundHalfUp((long) width * aspectRatioHeight, aspectRatioWidth);
            if (height >= 1 && height <= sourceHeight) {
                return List.of(new MediaRenditionDimensions(width, height));
            }
        }
        throw new IllegalArgumentException("source is too small for the required rendition");
    }

    private int roundHalfUp(long numerator, long denominator) {
        return Math.toIntExact((2 * numerator + denominator) / (2 * denominator));
    }
}
