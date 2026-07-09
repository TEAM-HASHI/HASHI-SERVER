package org.sopt.hashi.shared.storage;

import static java.time.temporal.ChronoUnit.SECONDS;

import java.time.Duration;
import java.util.Locale;
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
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";
    private static final Duration DEFAULT_PRESIGNED_URL_EXPIRATION = Duration.ofMinutes(5);
    private static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(5);

    public StorageProperties {
        region = defaultIfBlank(region, DEFAULT_REGION);
        bucket = defaultIfBlank(bucket, DEFAULT_BUCKET);
        cloudfrontDomain = normalizeCloudFrontDomain(cloudfrontDomain);
        if (presignedUrlExpiration == null) {
            presignedUrlExpiration = DEFAULT_PRESIGNED_URL_EXPIRATION;
        }
        if (maxFileSize == null) {
            maxFileSize = DEFAULT_MAX_FILE_SIZE;
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String normalizeCloudFrontDomain(String cloudfrontDomain) {
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank()) {
            throw new IllegalArgumentException("CloudFront domain must be configured.");
        }

        String trimmedDomain = cloudfrontDomain.trim();
        String lowerCaseDomain = trimmedDomain.toLowerCase(Locale.ROOT);
        if (lowerCaseDomain.startsWith(HTTP_PREFIX)) {
            throw new IllegalArgumentException("CloudFront domain must use HTTPS.");
        }
        String domainWithoutScheme = lowerCaseDomain.startsWith(HTTPS_PREFIX)
                ? trimmedDomain.substring(HTTPS_PREFIX.length())
                : trimmedDomain;
        String normalizedDomain = removeTrailingSlashes(domainWithoutScheme);

        if (normalizedDomain.isBlank()) {
            throw new IllegalArgumentException("CloudFront domain must be configured.");
        }
        return HTTPS_PREFIX + normalizedDomain;
    }

    private static String removeTrailingSlashes(String domain) {
        while (domain.endsWith("/")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain;
    }
}
