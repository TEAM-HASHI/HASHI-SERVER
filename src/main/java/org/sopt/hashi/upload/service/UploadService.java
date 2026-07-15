package org.sopt.hashi.upload.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.shared.storage.PresignedUploadInfo;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.sopt.hashi.upload.code.UploadErrorCode;
import org.sopt.hashi.upload.dto.IssuePresignedUrlsRequest;
import org.sopt.hashi.upload.dto.PresignedUrlResponse;
import org.sopt.hashi.upload.dto.PresignedUrlsResponse;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UploadService {

    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Map<String, String> SUPPORTED_IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final FileStorage fileStorage;
    private final StorageProperties storageProperties;

    public UploadService(FileStorage fileStorage, StorageProperties storageProperties) {
        this.fileStorage = fileStorage;
        this.storageProperties = storageProperties;
    }

    public PresignedUrlsResponse issuePresignedUrls(IssuePresignedUrlsRequest request) {
        UploadUsage usage = UploadUsage.from(request.usage())
                .orElseThrow(() -> new BusinessException(UploadErrorCode.UNSUPPORTED_USAGE));
        List<ValidatedFile> validatedFiles = request.files().stream()
                .map(this::validateFile)
                .toList();

        List<PresignedUrlResponse> uploads = validatedFiles.stream()
                .map(file -> issuePresignedUrl(usage, file))
                .toList();
        // S3 업로드 자격 발급 기록 — 남용(대량 발급) 관측용. URL·키는 자격 정보라 남기지 않는다
        log.info("presigned URL 발급. usage={}, count={}", usage, uploads.size());
        return new PresignedUrlsResponse(uploads);
    }

    private ValidatedFile validateFile(IssuePresignedUrlsRequest.FileRequest file) {
        String extension = resolveExtension(file.contentType());
        validateFileSize(file.fileSize());
        return new ValidatedFile(file.contentType(), file.fileSize(), extension);
    }

    private PresignedUrlResponse issuePresignedUrl(UploadUsage usage, ValidatedFile file) {
        String fileKey = generateFileKey(usage, file.extension());
        PresignedUploadInfo presignedUploadInfo = fileStorage.createPresignedUploadUrl(
                fileKey,
                file.contentType(),
                file.fileSize()
        );
        return PresignedUrlResponse.from(presignedUploadInfo);
    }

    private String resolveExtension(String contentType) {
        String extension = SUPPORTED_IMAGE_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException(UploadErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        return extension;
    }

    private void validateFileSize(long fileSize) {
        if (fileSize > storageProperties.maxFileSize().toBytes()) {
            throw new BusinessException(UploadErrorCode.FILE_SIZE_EXCEEDED);
        }
    }

    private String generateFileKey(UploadUsage usage, String extension) {
        return "uploads/%s/%s/%s.%s".formatted(
                usage.directory(),
                LocalDate.now().format(DATE_PATH_FORMATTER),
                UUID.randomUUID(),
                extension
        );
    }

    private record ValidatedFile(String contentType, long fileSize, String extension) {
    }
}
