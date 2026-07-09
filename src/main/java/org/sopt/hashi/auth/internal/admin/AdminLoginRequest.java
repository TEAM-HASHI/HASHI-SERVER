package org.sopt.hashi.auth.internal.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * 어드민 로그인 요청(ID/PW).
 */
record AdminLoginRequest(
        @NotBlank(message = "아이디는 필수입니다") String loginId,
        @NotBlank(message = "비밀번호는 필수입니다") String password) {
}
