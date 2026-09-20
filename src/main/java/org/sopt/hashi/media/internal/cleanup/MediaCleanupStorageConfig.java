package org.sopt.hashi.media.internal.cleanup;

import org.sopt.hashi.media.internal.storage.MediaOriginalStorageProperties;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hashi.media.cleanup", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({MediaCleanupProperties.class, MediaOriginalStorageProperties.class,
        StorageProperties.class})
public class MediaCleanupStorageConfig {

    @Bean(destroyMethod = "close")
    public MediaCleanupStorage mediaCleanupStorage(MediaCleanupProperties properties,
                                                   MediaOriginalStorageProperties original,
                                                   StorageProperties delivery) {
        if (original.bucket().equals(delivery.bucket()) || !original.region().equals(delivery.region())) {
            throw new IllegalArgumentException("cleanup requires separate buckets in the same region");
        }
        S3Client client = S3Client.builder().region(Region.of(original.region()))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(properties.storageApiTimeout())
                        .apiCallAttemptTimeout(properties.storageAttemptTimeout()))
                .build();
        try {
            return new S3MediaCleanupStorage(client, original.bucket(), delivery.bucket(),
                    properties.storageMaxPages(), properties.storagePageSize(), properties.storageWorkBudget());
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }
}
