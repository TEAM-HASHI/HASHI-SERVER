package org.sopt.hashi.shared.storage;

import java.time.Duration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class S3FileStorage implements FileStorage {

    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    public S3FileStorage(S3Presigner s3Presigner, StorageProperties storageProperties) {
        this.s3Presigner = s3Presigner;
        this.storageProperties = storageProperties;
    }

    @Override
    public PresignedUploadInfo createPresignedUploadUrl(String fileKey, String contentType, long contentLength) {
        String fileUrl = resolveFileUrl(fileKey);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(fileKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        Duration expiration = storageProperties.presignedUrlExpiration();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);

        return new PresignedUploadInfo(
                presignedRequest.url().toString(),
                fileKey,
                fileUrl,
                Math.toIntExact(expiration.toSeconds()),
                presignedRequest.httpRequest().method().name()
        );
    }

    @Override
    public String resolveFileUrl(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return null;
        }

        return storageProperties.cloudfrontDomain() + "/" + fileKey;
    }
}
