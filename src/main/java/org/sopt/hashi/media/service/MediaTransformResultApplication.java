package org.sopt.hashi.media.service;

import java.time.Duration;
import java.util.Objects;

public record MediaTransformResultApplication(
        MediaTransformResultDisposition disposition,
        Duration processingDuration
) {

    public MediaTransformResultApplication {
        Objects.requireNonNull(disposition);
        if (disposition == MediaTransformResultDisposition.APPLIED) {
            Objects.requireNonNull(processingDuration);
        } else if (processingDuration != null) {
            throw new IllegalArgumentException("stale results cannot have a processing duration");
        }
    }

    public static MediaTransformResultApplication applied(Duration processingDuration) {
        return new MediaTransformResultApplication(
                MediaTransformResultDisposition.APPLIED,
                processingDuration
        );
    }

    public static MediaTransformResultApplication stale() {
        return new MediaTransformResultApplication(
                MediaTransformResultDisposition.STALE,
                null
        );
    }
}
