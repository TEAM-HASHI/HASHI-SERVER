package org.sopt.hashi.shared.response;

import org.sopt.hashi.shared.error.SuccessCode;

/**
 * 성공 응답 봉투. {@code data}는 {@code null}이어도 항상 노출한다(클래스 단위 NON_NULL 미적용).
 */
public record SuccessResponse<T>(boolean success, String code, String message, T data)
        implements BaseResponse {

    public static <T> SuccessResponse<T> of(SuccessCode code, T data) {
        return new SuccessResponse<>(true, code.getCode(), code.getMessage(), data);
    }

    public static SuccessResponse<Void> of(SuccessCode code) {
        return new SuccessResponse<>(true, code.getCode(), code.getMessage(), null);
    }
}
