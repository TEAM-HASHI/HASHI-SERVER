package org.sopt.hashi.media.internal.queue;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.sopt.hashi.media.domain.MediaPurpose;

@JsonPropertyOrder({
        "contractVersion",
        "jobId",
        "assetId",
        "purpose",
        "specVersion",
        "specDigest",
        "originalKey",
        "sourceVersionId",
        "sourceETag",
        "declaredContentType",
        "declaredByteSize"
})
public record MediaTransformRequest(
        int contractVersion,
        UUID jobId,
        UUID assetId,
        MediaPurpose purpose,
        int specVersion,
        String specDigest,
        String originalKey,
        String sourceVersionId,
        String sourceETag,
        String declaredContentType,
        long declaredByteSize
) {

    public static final int CURRENT_CONTRACT_VERSION = 1;
    private static final Set<String> SOURCE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    public MediaTransformRequest {
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw new IllegalArgumentException("unsupported media transform contract version");
        }
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (specVersion < 1) {
            throw new IllegalArgumentException("specVersion must be positive");
        }
        requirePattern(specDigest, "[0-9a-f]{64}", "specDigest");
        String expectedOriginalKey = "media/originals/%s/original".formatted(assetId);
        if (!expectedOriginalKey.equals(originalKey)) {
            throw new IllegalArgumentException("originalKey does not match assetId");
        }
        requireBoundedText(sourceVersionId, 1, 1024, "sourceVersionId");
        requireBoundedText(sourceETag, 1, 255, "sourceETag");
        if (!SOURCE_CONTENT_TYPES.contains(declaredContentType)) {
            throw new IllegalArgumentException("declaredContentType is unsupported");
        }
        long maxBytes = purpose == MediaPurpose.MAGAZINE_CARD_NEWS && specVersion >= 2
                ? 10L * 1024 * 1024
                : 5L * 1024 * 1024;
        if (declaredByteSize < 1 || declaredByteSize > maxBytes) {
            throw new IllegalArgumentException("declaredByteSize is outside the worker limit");
        }
    }

    private static void requirePattern(String value, String pattern, String fieldName) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(fieldName + " does not match the queue contract");
        }
    }

    private static void requireBoundedText(
            String value, int minimum, int maximum, String fieldName) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " does not match the queue contract");
        }
    }
}
