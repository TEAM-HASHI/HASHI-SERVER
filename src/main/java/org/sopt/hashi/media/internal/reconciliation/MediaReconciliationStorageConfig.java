package org.sopt.hashi.media.internal.reconciliation;

import org.sopt.hashi.media.internal.storage.MediaOriginalStorageProperties;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hashi.media.reconciliation", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({MediaReconciliationProperties.class, MediaOriginalStorageProperties.class,
        StorageProperties.class})
public class MediaReconciliationStorageConfig {

    @Bean(destroyMethod = "close")
    public MediaReconciliationStorage mediaReconciliationStorage(
            MediaReconciliationProperties properties,
            MediaOriginalStorageProperties original,
            StorageProperties delivery
    ) {
        if (original.bucket().equals(delivery.bucket()) || !original.region().equals(delivery.region())) {
            throw new IllegalArgumentException("reconciliation requires separate buckets in the same region");
        }
        S3Client client = S3Client.builder().region(Region.of(original.region()))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(properties.storageApiTimeout())
                        .apiCallAttemptTimeout(properties.storageAttemptTimeout()))
                .build();
        try {
            return new S3MediaReconciliationStorage(client, original.bucket(), delivery.bucket());
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }
}
