package org.sopt.hashi.shared.response;

/**
 * 모든 API 응답의 최상위 계약. {@link SuccessResponse}/{@link ErrorResponse}만 구현한다(sealed).
 * HttpStatus는 {@code ResponseEntity}가 전달하므로 응답 바디에 두지 않는다(중복 방지).
 */
public sealed interface BaseResponse permits SuccessResponse, ErrorResponse {

    boolean success();

    String code();

    String message();
}
