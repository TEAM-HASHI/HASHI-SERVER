package org.sopt.hashi.media.internal.storage;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class S3MediaOriginalStorage implements MediaOriginalStorage {

    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final String CONTENT_LENGTH_HEADER = "content-length";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";
    private static final Set<String> REQUIRED_SIGNED_HEADERS = Set.of(
            CONTENT_TYPE_HEADER,
            CONTENT_LENGTH_HEADER,
            IF_NONE_MATCH_HEADER
    );

    private final S3Presigner presigner;
    private final S3Client s3Client;
    private final MediaOriginalStorageProperties properties;

    public S3MediaOriginalStorage(S3Presigner presigner, S3Client s3Client,
                                  MediaOriginalStorageProperties properties) {
        this.presigner = presigner;
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public PresignedOriginalUpload createPresignedUpload(
            String objectKey,
            String contentType,
            long contentLength
    ) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .overrideConfiguration(configuration ->
                            configuration.putHeader("If-None-Match", "*"))
                    .build();

            Duration expiration = properties.presignedUrlExpiration();
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(expiration)
                    .putObjectRequest(putObjectRequest)
                    .build();
            PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);
            verifyRequiredHeadersAreSigned(presignedRequest);

            return new PresignedOriginalUpload(
                    presignedRequest.url().toString(),
                    Map.of(
                            "Content-Type", contentType,
                            "If-None-Match", "*"
                    ),
                    contentLength,
                    Math.toIntExact(expiration.toSeconds()),
                    presignedRequest.httpRequest().method().name()
            );
        } catch (SdkException | ArithmeticException e) {
            throw new MediaStorageUnavailableException("failed to issue media upload URL", e);
        }
    }

    @Override
    public Optional<OriginalObjectMetadata> findObjectMetadata(String objectKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
            return Optional.of(new OriginalObjectMetadata(
                    objectKey,
                    response.versionId(),
                    response.eTag(),
                    response.contentType(),
                    response.contentLength()
            ));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new MediaStorageUnavailableException("failed to inspect media original", e);
        } catch (SdkException e) {
            throw new MediaStorageUnavailableException("failed to inspect media original", e);
        }
    }

    @Override
    public void close() {
        presigner.close();
        s3Client.close();
    }

    private void verifyRequiredHeadersAreSigned(PresignedPutObjectRequest presignedRequest) {
        Set<String> signedHeaders = presignedRequest.signedHeaders().keySet().stream()
                .map(header -> header.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!signedHeaders.containsAll(REQUIRED_SIGNED_HEADERS)) {
            throw new MediaStorageUnavailableException("media upload URL is missing required signed headers");
        }
    }
}
