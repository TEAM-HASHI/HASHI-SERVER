package org.sopt.hashi.shared.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.shared.error.ErrorCode;

/**
 * 실패 응답 봉투. {@code data}를 제외한 {@code null} 필드는 직렬화에서 제외한다(클래스 단위 NON_NULL).
 * {@code data}는 항상 {@code null}이지만 성공 응답과의 형태 일관성을 위해 ALWAYS로 노출한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        Object data,
        LocalDateTime timestamp,
        String path,
        List<FieldError> errors
) implements BaseResponse {

    /**
     * 필드 단위 검증 에러(도메인 무관 — shared에 둔다).
     */
    public record FieldError(String field, Object rejectedValue, String reason) {
    }

    public static ErrorResponse of(ErrorCode code, String path) {
        return new ErrorResponse(false, code.getCode(), code.getMessage(), null, LocalDateTime.now(), path, null);
    }

    public static ErrorResponse of(ErrorCode code, String path, List<FieldError> errors) {
        return new ErrorResponse(false, code.getCode(), code.getMessage(), null, LocalDateTime.now(), path, errors);
    }
}
