package org.sopt.hashi.auth.internal.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 어드민 로그인 요청(ID/PW). */
record AdminLoginRequest(
        @Schema(description = "어드민 로그인 ID", example = "admin")
        @NotBlank(message = "아이디는 필수입니다") String loginId,
        @Schema(description = "비밀번호", example = "password1234")
        @NotBlank(message = "비밀번호는 필수입니다") String password) {
}
