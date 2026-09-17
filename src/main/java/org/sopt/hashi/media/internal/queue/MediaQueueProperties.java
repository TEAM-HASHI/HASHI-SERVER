package org.sopt.hashi.media.internal.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.media.queue")
public record MediaQueueProperties(
        boolean enabled,
        String requestQueueUrl,
        String resultQueueUrl,
        int publisherCorePoolSize,
        int publisherMaxPoolSize,
        int publisherQueueCapacity,
        Duration publisherShutdownAwait
) {

    private static final int DEFAULT_PUBLISHER_CORE_POOL_SIZE = 2;
    private static final int DEFAULT_PUBLISHER_MAX_POOL_SIZE = 4;
    private static final int DEFAULT_PUBLISHER_QUEUE_CAPACITY = 100;
    private static final Duration DEFAULT_PUBLISHER_SHUTDOWN_AWAIT = Duration.ofSeconds(20);

    public MediaQueueProperties {
        requestQueueUrl = trimToNull(requestQueueUrl);
        resultQueueUrl = trimToNull(resultQueueUrl);
        publisherCorePoolSize = defaultIfZero(
                publisherCorePoolSize, DEFAULT_PUBLISHER_CORE_POOL_SIZE);
        publisherMaxPoolSize = defaultIfZero(
                publisherMaxPoolSize, DEFAULT_PUBLISHER_MAX_POOL_SIZE);
        publisherQueueCapacity = defaultIfZero(
                publisherQueueCapacity, DEFAULT_PUBLISHER_QUEUE_CAPACITY);
        if (publisherShutdownAwait == null) {
            publisherShutdownAwait = DEFAULT_PUBLISHER_SHUTDOWN_AWAIT;
        }
        if (enabled && (requestQueueUrl == null || resultQueueUrl == null)) {
            throw new IllegalArgumentException(
                    "media queue URLs must be configured when the media queue is enabled");
        }
        if (publisherCorePoolSize < 1
                || publisherMaxPoolSize < publisherCorePoolSize
                || publisherQueueCapacity < 1
                || publisherShutdownAwait.isZero()
                || publisherShutdownAwait.isNegative()) {
            throw new IllegalArgumentException("media queue publisher settings are invalid");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static int defaultIfZero(int value, int defaultValue) {
        return value == 0 ? defaultValue : value;
    }
}
