package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 온보딩(가입) 요청 — 영문 이름·프로필 사진은 선택. profileImageKey는 업로드 완료된 S3 object key. */
public record CompleteOnboardingRequest(
        @Schema(description = "닉네임", example = "김하람")
        @NotBlank(message = "닉네임은 필수입니다") @Size(max = 50) String nickname,
        @Schema(description = "영문 이름(선택)", example = "HARAM KIM")
        @Size(max = 20) String nameEng,
        @Schema(description = "생년월일", example = "1998-01-01")
        @NotNull(message = "생년월일은 필수입니다") @Past(message = "생년월일은 과거 날짜여야 합니다") LocalDate birthDate,
        @Schema(description = "연락처 — 하이픈 없이 숫자 10~11자리", example = "01078757856")
        @NotBlank(message = "연락처는 필수입니다")
        @Pattern(regexp = "^0\\d{9,10}$", message = "연락처는 하이픈 없이 숫자 10~11자리로 입력해주세요") String phone,
        @Schema(description = "이메일", example = "hashi@example.com")
        @NotBlank(message = "이메일은 필수입니다") @Email @Size(max = 255) String email,
        @Schema(description = "프로필 사진 S3 key(선택) — presigned URL로 업로드를 마친 key", example = "profiles/a1b2c3-profile.jpg")
        @Size(max = 500) String profileImageKey) {
}
