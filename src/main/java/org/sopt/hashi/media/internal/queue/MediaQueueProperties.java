package org.sopt.hashi.media.internal.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.media.queue")
public record MediaQueueProperties(
        boolean enabled,
        String requestQueueUrl,
        String resultQueueUrl
) {

    public MediaQueueProperties {
        requestQueueUrl = trimToNull(requestQueueUrl);
        resultQueueUrl = trimToNull(resultQueueUrl);
        if (enabled && (requestQueueUrl == null || resultQueueUrl == null)) {
            throw new IllegalArgumentException(
                    "media queue URLs must be configured when the media queue is enabled");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
