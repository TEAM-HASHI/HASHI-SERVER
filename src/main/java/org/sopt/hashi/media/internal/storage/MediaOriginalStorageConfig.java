package org.sopt.hashi.media.internal.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(MediaOriginalStorageProperties.class)
public class MediaOriginalStorageConfig {

    @Bean(destroyMethod = "close")
    public MediaOriginalStorage mediaOriginalStorage(MediaOriginalStorageProperties properties) {
        Region region = Region.of(properties.region());
        S3Presigner presigner = S3Presigner.builder()
                .region(region)
                .build();
        S3Client s3Client = S3Client.builder()
                .region(region)
                .build();
        return new S3MediaOriginalStorage(presigner, s3Client, properties);
    }
}
