package org.sopt.hashi.media.internal.backfill;

import org.sopt.hashi.media.internal.storage.MediaOriginalStorageProperties;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hashi.media.backfill", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({MediaOriginalStorageProperties.class, StorageProperties.class})
public class MediaBackfillStorageConfig {

    @Bean(destroyMethod = "close")
    public MediaBackfillStorage mediaBackfillStorage(
            MediaOriginalStorageProperties original, StorageProperties delivery) {
        if (original.bucket().equals(delivery.bucket())) {
            throw new IllegalArgumentException("backfill requires separate delivery and original buckets");
        }
        if (!original.region().equals(delivery.region())) {
            throw new IllegalArgumentException("backfill requires delivery and original buckets in the same region");
        }
        S3Client client = S3Client.builder().region(Region.of(original.region())).build();
        return new S3MediaBackfillStorage(client, delivery.bucket(), original.bucket());
    }
}
