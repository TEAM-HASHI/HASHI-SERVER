package org.sopt.hashi.media.internal.spec;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MediaRoleSpec(
        int aspectRatioWidth,
        int aspectRatioHeight,
        int defaultWidth,
        int minimumFallbackWidth,
        List<MediaRenditionDimensions> candidates,
        String fit,
        String fallbackSelection
) {

    public MediaRoleSpec(int aspectRatioWidth, int aspectRatioHeight, int defaultWidth,
                         int minimumFallbackWidth, List<MediaRenditionDimensions> candidates) {
        this(aspectRatioWidth, aspectRatioHeight, defaultWidth, minimumFallbackWidth,
                candidates, "cover", "largest-croppable-width");
    }

    public MediaRoleSpec {
        if (aspectRatioWidth < 1 || aspectRatioHeight < 1
                || defaultWidth < 1 || minimumFallbackWidth < 1) {
            throw new IllegalArgumentException("media role dimensions must be positive");
        }
        if (!"cover".equals(fit) && !"inside".equals(fit)) {
            throw new IllegalArgumentException("media role fit is unsupported");
        }
        if (!"largest-croppable-width".equals(fallbackSelection)
                && !"source-width".equals(fallbackSelection)) {
            throw new IllegalArgumentException("media role fallback selection is unsupported");
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
        Map<Integer, MediaRenditionDimensions> byWidth = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> "inside".equals(fit)
                        || candidate.width() <= sourceWidth && candidate.height() <= sourceHeight)
                .map(candidate -> outputDimensions(sourceWidth, sourceHeight, candidate))
                .forEach(output -> byWidth.merge(output.width(), output,
                        (existing, candidate) -> candidate.height() > existing.height()
                                ? candidate : existing));
        List<MediaRenditionDimensions> standard = List.copyOf(byWidth.values());
        if (!standard.isEmpty()) {
            return standard;
        }

        if ("source-width".equals(fallbackSelection)) {
            return List.of(new MediaRenditionDimensions(sourceWidth, sourceHeight));
        }

        for (int width = sourceWidth; width >= minimumFallbackWidth; width--) {
            int height = roundHalfUp((long) width * aspectRatioHeight, aspectRatioWidth);
            if (height >= 1 && height <= sourceHeight) {
                return List.of(new MediaRenditionDimensions(width, height));
            }
        }
        throw new IllegalArgumentException("source is too small for the required rendition");
    }

    public MediaRenditionDimensions defaultOutputDimensions(int sourceWidth, int sourceHeight) {
        MediaRenditionDimensions defaultCandidate = candidates.stream()
                .filter(candidate -> candidate.width() == defaultWidth)
                .findFirst()
                .orElseThrow();
        if (!"inside".equals(fit)) {
            return defaultCandidate;
        }
        int outputWidth = outputDimensions(sourceWidth, sourceHeight, defaultCandidate).width();
        return selectFor(sourceWidth, sourceHeight).stream()
                .filter(output -> output.width() == outputWidth)
                .findFirst()
                .orElseThrow();
    }

    private MediaRenditionDimensions outputDimensions(
            int sourceWidth, int sourceHeight, MediaRenditionDimensions target) {
        if ("cover".equals(fit)) {
            return target;
        }
        if (sourceWidth <= target.width() && sourceHeight <= target.height()) {
            return new MediaRenditionDimensions(sourceWidth, sourceHeight);
        }
        if ((long) sourceWidth * target.height() >= (long) sourceHeight * target.width()) {
            int width = Math.min(sourceWidth, target.width());
            return new MediaRenditionDimensions(width,
                    Math.max(1, roundHalfUp((long) width * sourceHeight, sourceWidth)));
        }
        int height = Math.min(sourceHeight, target.height());
        return new MediaRenditionDimensions(
                Math.max(1, roundHalfUp((long) height * sourceWidth, sourceHeight)), height);
    }

    private int roundHalfUp(long numerator, long denominator) {
        return Math.toIntExact((2 * numerator + denominator) / (2 * denominator));
    }
}
