package org.sopt.hashi.shared.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public S3Presigner s3Presigner(StorageProperties storageProperties) {
        return S3Presigner.builder()
                .region(Region.of(storageProperties.region()))
                .build();
    }

    @Bean
    public FileStorage fileStorage(S3Presigner s3Presigner, StorageProperties storageProperties) {
        return new S3FileStorage(s3Presigner, storageProperties);
    }
}
