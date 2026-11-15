package org.sopt.hashi.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.sopt.hashi.user.code.UserErrorCode;

/**
 * 필드별 사용 가능 여부 응답 — 요청에 없던 필드는 null이라 직렬화에서 생략한다.
 * 사용 불가일 때만 중복 에러 코드의 문구(기능명세 항목별 문구)를 message로 함께 내려 클라이언트가 입력창별로 표시한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileAvailabilityResponse(
        FieldAvailability nickname,
        FieldAvailability phone,
        FieldAvailability email) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldAvailability(
            @Schema(description = "사용 가능 여부", example = "false") boolean available,
            @Schema(description = "사용 불가 사유 — 사용 가능이면 생략", example = "중복된 닉네임입니다.") String message) {

        public static FieldAvailability of(boolean available, UserErrorCode duplicateCode) {
            return new FieldAvailability(available, available ? null : duplicateCode.getMessage());
        }
    }
}
