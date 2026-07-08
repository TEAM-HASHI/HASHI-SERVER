package org.sopt.hashi.shared.storage;

import static java.time.temporal.ChronoUnit.SECONDS;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "hashi.storage")
public record StorageProperties(
        String region,
        String bucket,
        String cloudfrontDomain,
        @DurationUnit(SECONDS) Duration presignedUrlExpiration,
        DataSize maxFileSize
) {

    private static final String DEFAULT_REGION = "ap-northeast-2";
    private static final String DEFAULT_BUCKET = "hashi-local-uploads";
    private static final Duration DEFAULT_PRESIGNED_URL_EXPIRATION = Duration.ofMinutes(5);
    private static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(5);

    public StorageProperties {
        if (region == null || region.isBlank()) {
            region = DEFAULT_REGION;
        }
        if (bucket == null || bucket.isBlank()) {
            bucket = DEFAULT_BUCKET;
        }
        if (cloudfrontDomain == null) {
            cloudfrontDomain = "";
        }
        if (presignedUrlExpiration == null) {
            presignedUrlExpiration = DEFAULT_PRESIGNED_URL_EXPIRATION;
        }
        if (maxFileSize == null) {
            maxFileSize = DEFAULT_MAX_FILE_SIZE;
        }
    }
}
