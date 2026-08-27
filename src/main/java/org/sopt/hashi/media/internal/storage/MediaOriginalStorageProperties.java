package org.sopt.hashi.media.internal.storage;

import static java.time.temporal.ChronoUnit.SECONDS;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "hashi.media.original-storage")
public record MediaOriginalStorageProperties(
        String region,
        String bucket,
        @DurationUnit(SECONDS) Duration presignedUrlExpiration,
        DataSize maxFileSize,
        Integer maxFilesPerRequest
) {

    private static final String DEFAULT_REGION = "ap-northeast-2";
    private static final String DEFAULT_BUCKET = "hashi-local-media-originals";
    private static final Duration DEFAULT_PRESIGNED_URL_EXPIRATION = Duration.ofMinutes(5);
    private static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(5);
    private static final int DEFAULT_MAX_FILES_PER_REQUEST = 10;

    public MediaOriginalStorageProperties {
        region = defaultIfBlank(region, DEFAULT_REGION);
        bucket = defaultIfBlank(bucket, DEFAULT_BUCKET);
        if (presignedUrlExpiration == null) {
            presignedUrlExpiration = DEFAULT_PRESIGNED_URL_EXPIRATION;
        }
        if (maxFileSize == null) {
            maxFileSize = DEFAULT_MAX_FILE_SIZE;
        }
        if (maxFilesPerRequest == null) {
            maxFilesPerRequest = DEFAULT_MAX_FILES_PER_REQUEST;
        }
        if (presignedUrlExpiration.isZero() || presignedUrlExpiration.isNegative()) {
            throw new IllegalArgumentException("media presigned URL expiration must be positive");
        }
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("media max file size must be positive");
        }
        if (maxFilesPerRequest < 1 || maxFilesPerRequest > 10) {
            throw new IllegalArgumentException("media max files per request must be between 1 and 10");
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
