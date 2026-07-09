package org.sopt.hashi.upload.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.shared.storage.PresignedUploadInfo;
import org.sopt.hashi.shared.storage.StorageProperties;
import org.sopt.hashi.upload.code.UploadErrorCode;
import org.sopt.hashi.upload.dto.IssuePresignedUrlRequest;
import org.sopt.hashi.upload.dto.PresignedUrlResponse;
import org.springframework.stereotype.Service;

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

    public PresignedUrlResponse issuePresignedUrl(IssuePresignedUrlRequest request) {
        UploadUsage usage = UploadUsage.from(request.usage())
                .orElseThrow(() -> new BusinessException(UploadErrorCode.UNSUPPORTED_USAGE));
        String extension = resolveExtension(request.contentType());
        validateFileSize(request.fileSize());

        String fileKey = generateFileKey(usage, extension);
        PresignedUploadInfo presignedUploadInfo = fileStorage.createPresignedUploadUrl(
                fileKey,
                request.contentType(),
                request.fileSize()
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
}
