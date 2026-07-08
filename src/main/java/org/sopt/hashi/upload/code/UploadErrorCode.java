package org.sopt.hashi.upload.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum UploadErrorCode implements ErrorCode {

    UNSUPPORTED_USAGE(HttpStatus.BAD_REQUEST, "UPLOAD-001", "지원하지 않는 파일 사용 목적입니다."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "UPLOAD-002", "지원하지 않는 파일 형식입니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "UPLOAD-003", "업로드 가능한 파일 크기를 초과했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UploadErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
