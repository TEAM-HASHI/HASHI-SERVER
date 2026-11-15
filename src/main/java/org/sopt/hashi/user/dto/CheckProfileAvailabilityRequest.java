package org.sopt.hashi.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 닉네임·연락처·이메일 사용 가능 여부 확인 요청 — 모두 선택이며 전달된 필드만 검사한다(최소 1개).
 * 형식 규칙은 온보딩 요청과 같다. 클라이언트는 필드별 입력 시점(디바운스·blur)에 검사 대상 필드만 보낸다.
 */
public record CheckProfileAvailabilityRequest(
        @Schema(description = "닉네임(선택, 공백 불가)", example = "김하람")
        @Pattern(regexp = "(?sU).*\\S.*", message = "닉네임은 공백일 수 없습니다")
        @Size(max = 50, message = "닉네임은 50자 이내입니다") String nickname,
        @Schema(description = "연락처(선택) — 하이픈 없이 숫자 10~11자리", example = "01078757856")
        @Pattern(regexp = "^0\\d{9,10}$", message = "연락처는 하이픈 없이 숫자 10~11자리로 입력해주세요") String phone,
        @Schema(description = "이메일(선택)", example = "hashi@example.com")
        @Pattern(regexp = "(?sU).*\\S.*", message = "이메일은 공백일 수 없습니다")
        @Email(message = "이메일 형식이 아닙니다")
        @Size(max = 255, message = "이메일은 255자 이내입니다") String email) {

    @AssertTrue(message = "검사할 필드를 하나 이상 보내주세요")
    @JsonIgnore
    public boolean isAnyFieldPresent() {
        return nickname != null || phone != null || email != null;
    }
}
