package org.sopt.hashi.media.internal.backfill;

import java.util.Locale;
import java.util.Set;

/** HEAD로 관찰한 legacy source. 원시 경로는 영속화하거나 로그에 출력하지 않는다. */
public record LegacyImageSource(
        String bucket,
        String objectKey,
        String versionId,
        String eTag,
        String contentType,
        long bytes
) {

    private static final long MAX_SOURCE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> SUPPORTED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    public LegacyImageSource {
        boolean validLocation = hasText(bucket) && hasText(objectKey)
                && !objectKey.startsWith("media/");
        if (!validLocation || !hasText(eTag) || eTag.contains("\r") || eTag.contains("\n")) {
            throw new IllegalArgumentException("invalid legacy source identity");
        }
        // S3의 null version은 불변 version이 아니므로 ETag 조건으로 고정한다.
        if ("null".equals(versionId)) {
            versionId = null;
        }
        if (versionId != null && !hasText(versionId)) {
            throw new IllegalArgumentException("invalid legacy source version");
        }
        contentType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(contentType) || bytes < 1 || bytes > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("legacy source is outside the image contract");
        }
    }

    @Override
    public String toString() {
        return "LegacyImageSource[redacted]";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
