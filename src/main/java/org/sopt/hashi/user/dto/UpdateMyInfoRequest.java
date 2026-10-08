package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 내 정보 부분 수정(PATCH) 요청 — null 필드는 변경하지 않는다(식당·매거진·컬렉션과 동일 정책).
 * 영문 이름은 수정 화면 범위 밖이라 받지 않고(MYP-002), 사진 제거는 별도 DELETE로 한다.
 * profileImageAssetId는 media에 업로드를 마친 PROFILE asset이며 보내면 기존 사진을 교체한다.
 */
public record UpdateMyInfoRequest(
        @Schema(description = "닉네임(선택, 공백 불가)", example = "김하람")
        @Pattern(regexp = "(?sU).*\\S.*", message = "닉네임은 공백일 수 없습니다")
        @Size(max = 50, message = "닉네임은 50자 이내입니다") String nickname,
        @Schema(description = "생년월일(선택)", example = "1998-01-01")
        @Past(message = "생년월일은 과거 날짜여야 합니다") LocalDate birthDate,
        @Schema(description = "연락처(선택) — 하이픈 없이 숫자 10~11자리", example = "01078757856")
        @Pattern(regexp = "^0\\d{9,10}$", message = "연락처는 하이픈 없이 숫자 10~11자리로 입력해주세요") String phone,
        @Schema(description = "이메일(선택)", example = "hashi@example.com")
        @Pattern(regexp = "(?sU).*\\S.*", message = "이메일은 공백일 수 없습니다")
        @Email(message = "이메일 형식이 아닙니다")
        @Size(max = 255, message = "이메일은 255자 이내입니다") String email,
        @Schema(description = "새 프로필 사진 public asset ID(선택, 보내면 교체)",
                example = "a3af06f1-4ef2-46f8-a489-2347fb840447")
        UUID profileImageAssetId) {
}
