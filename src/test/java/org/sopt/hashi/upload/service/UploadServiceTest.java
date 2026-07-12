package org.sopt.hashi.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.shared.storage.PresignedUploadInfo;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.sopt.hashi.upload.code.UploadErrorCode;
import org.sopt.hashi.upload.dto.IssuePresignedUrlsRequest;
import org.sopt.hashi.upload.dto.PresignedUrlResponse;
import org.sopt.hashi.upload.dto.PresignedUrlsResponse;
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
    void 여러_파일의_presigned_URL을_요청_순서대로_발급한다() {
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest(
                "review",
                List.of(
                        file("image/jpeg", 1024L),
                        file("image/png", 2048L)
                )
        );

        PresignedUrlsResponse response = uploadService.issuePresignedUrls(request);

        assertThat(response.uploads()).hasSize(2);
        PresignedUrlResponse first = response.uploads().get(0);
        PresignedUrlResponse second = response.uploads().get(1);
        assertThat(first.uploadMethod()).isEqualTo("PUT");
        assertThat(first.expiresInSeconds()).isEqualTo(300);
        assertThat(first.fileKey()).startsWith("uploads/reviews/").endsWith(".jpg");
        assertThat(first.fileUrl()).isEqualTo("https://cdn.example.com/" + first.fileKey());
        assertThat(second.fileKey()).startsWith("uploads/reviews/").endsWith(".png");
        assertThat(fileStorage.callCount()).isEqualTo(2);
    }

    @Test
    void 지원하지_않는_사용_목적이면_예외가_발생한다() {
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest(
                "unknown",
                List.of(file("image/jpeg", 1024L))
        );

        assertThatThrownBy(() -> uploadService.issuePresignedUrls(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UploadErrorCode.UNSUPPORTED_USAGE));
        assertThat(fileStorage.callCount()).isZero();
    }

    @Test
    void 파일_목록에_지원하지_않는_형식이_있으면_어떤_URL도_발급하지_않는다() {
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest(
                "review",
                List.of(
                        file("image/jpeg", 1024L),
                        file("image/gif", 1024L)
                )
        );

        assertThatThrownBy(() -> uploadService.issuePresignedUrls(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UploadErrorCode.UNSUPPORTED_FILE_TYPE));
        assertThat(fileStorage.callCount()).isZero();
    }

    @Test
    void 파일_크기가_제한을_초과하면_예외가_발생한다() {
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest(
                "review",
                List.of(file("image/jpeg", DataSize.ofMegabytes(6).toBytes()))
        );

        assertThatThrownBy(() -> uploadService.issuePresignedUrls(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UploadErrorCode.FILE_SIZE_EXCEEDED));
        assertThat(fileStorage.callCount()).isZero();
    }

    private static IssuePresignedUrlsRequest.FileRequest file(String contentType, long fileSize) {
        return new IssuePresignedUrlsRequest.FileRequest(contentType, fileSize);
    }

    private static class FakeFileStorage implements FileStorage {

        private int callCount;

        @Override
        public PresignedUploadInfo createPresignedUploadUrl(String fileKey, String contentType, long contentLength) {
            callCount++;
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

        int callCount() {
            return callCount;
        }
    }
}
