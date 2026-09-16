package org.sopt.hashi.media.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum MediaErrorCode implements ErrorCode {

    ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, "MEDIA-001", "이미지 자산을 찾을 수 없습니다"),
    PURPOSE_FORBIDDEN(HttpStatus.FORBIDDEN, "MEDIA-002", "해당 용도의 이미지를 업로드할 권한이 없습니다"),
    UPLOAD_NOT_FOUND(HttpStatus.BAD_REQUEST, "MEDIA-003", "업로드된 원본 이미지를 찾을 수 없습니다"),
    UPLOAD_METADATA_MISMATCH(HttpStatus.BAD_REQUEST, "MEDIA-004", "업로드된 이미지 정보가 발급 요청과 다릅니다"),
    ASSET_EXPIRED(HttpStatus.CONFLICT, "MEDIA-005", "이미지 업로드 가능 시간이 만료되었습니다"),
    INVALID_STATE(HttpStatus.CONFLICT, "MEDIA-006", "현재 이미지 상태에서는 요청을 처리할 수 없습니다"),
    ALREADY_BOUND(HttpStatus.CONFLICT, "MEDIA-007", "이미 사용 중이거나 사용이 끝난 이미지입니다"),
    DUPLICATE_ASSET(HttpStatus.BAD_REQUEST, "MEDIA-008", "같은 이미지 자산을 중복해서 요청할 수 없습니다"),
    PIPELINE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA-009", "이미지 처리 기능을 잠시 사용할 수 없습니다"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "MEDIA-010", "지원하지 않는 이미지 형식입니다"),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "MEDIA-011", "업로드 가능한 이미지 크기를 초과했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MediaErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
