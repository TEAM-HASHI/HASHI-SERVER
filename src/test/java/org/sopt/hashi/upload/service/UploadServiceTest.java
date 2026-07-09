package org.sopt.hashi.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.shared.storage.PresignedUploadInfo;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.sopt.hashi.upload.code.UploadErrorCode;
import org.sopt.hashi.upload.dto.IssuePresignedUrlRequest;
import org.sopt.hashi.upload.dto.PresignedUrlResponse;
import org.springframework.util.unit.DataSize;

class UploadServiceTest {

    private final FakeFileStorage fileStorage = new FakeFileStorage();
    private final StorageProperties storageProperties = new StorageProperties(
            "ap-northeast-2",
            "hashi-dev-uploads",
            "d111111abcdef8.cloudfront.net",
            Duration.ofMinutes(5),
            DataSize.ofMegabytes(5)
    );
    private final UploadService uploadService = new UploadService(fileStorage, storageProperties);

    @Test
    void presigned_URL을_발급한다() {
        IssuePresignedUrlRequest request = new IssuePresignedUrlRequest(
                "review",
                "image/jpeg",
                1024L
        );

        PresignedUrlResponse response = uploadService.issuePresignedUrl(request);

        assertThat(response.uploadMethod()).isEqualTo("PUT");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        assertThat(response.fileKey()).startsWith("uploads/reviews/");
        assertThat(response.fileKey()).endsWith(".jpg");
        assertThat(response.fileUrl()).isEqualTo("https://cdn.example.com/" + response.fileKey());
    }

    @Test
    void 지원하지_않는_사용_목적이면_예외가_발생한다() {
        IssuePresignedUrlRequest request = new IssuePresignedUrlRequest(
                "unknown",
                "image/jpeg",
                1024L
        );

        assertThatThrownBy(() -> uploadService.issuePresignedUrl(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UploadErrorCode.UNSUPPORTED_USAGE));
    }

    @Test
    void 지원하지_않는_파일_형식이면_예외가_발생한다() {
        IssuePresignedUrlRequest request = new IssuePresignedUrlRequest(
                "review",
                "image/gif",
                1024L
        );

        assertThatThrownBy(() -> uploadService.issuePresignedUrl(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UploadErrorCode.UNSUPPORTED_FILE_TYPE));
    }

    @Test
    void 파일_크기가_제한을_초과하면_예외가_발생한다() {
        IssuePresignedUrlRequest request = new IssuePresignedUrlRequest(
                "review",
                "image/jpeg",
                DataSize.ofMegabytes(6).toBytes()
        );

        assertThatThrownBy(() -> uploadService.issuePresignedUrl(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UploadErrorCode.FILE_SIZE_EXCEEDED));
    }

    private static class FakeFileStorage implements FileStorage {

        @Override
        public PresignedUploadInfo createPresignedUploadUrl(String fileKey, String contentType, long contentLength) {
            return new PresignedUploadInfo(
                    "https://s3.example.com/" + fileKey + "?signature=test",
                    fileKey,
                    resolveFileUrl(fileKey),
                    300,
                    "PUT"
            );
        }

        @Override
        public String resolveFileUrl(String fileKey) {
            return "https://cdn.example.com/" + fileKey;
        }
    }
}
