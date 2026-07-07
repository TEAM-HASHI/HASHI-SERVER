package org.sopt.hashi.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 온보딩(가입) 요청. 닉네임·생년월일·연락처·이메일은 필수, 영문 이름·프로필 사진은 선택이다.
 * profileImageKey는 업로드 완료된 S3 object key(presigned URL 아님).
 * phone은 하이픈 없는 숫자, 앞자리 0·10~11자리로 받는다(정규화·유선번호 정책은 별도 합의 전 최소 규칙).
 */
public record CompleteOnboardingRequest(
        @NotBlank(message = "닉네임은 필수입니다") @Size(max = 50) String nickname,
        @Size(max = 20) String nameEng,
        @NotNull(message = "생년월일은 필수입니다") @Past(message = "생년월일은 과거 날짜여야 합니다") LocalDate birthDate,
        @NotBlank(message = "연락처는 필수입니다")
        @Pattern(regexp = "^0\\d{9,10}$", message = "연락처는 하이픈 없이 숫자 10~11자리로 입력해주세요") String phone,
        @NotBlank(message = "이메일은 필수입니다") @Email @Size(max = 255) String email,
        @Size(max = 500) String profileImageKey) {
}
