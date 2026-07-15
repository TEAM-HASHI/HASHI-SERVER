package org.sopt.hashi.auth.internal.kakao;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 카카오 로그인 요청. */
public record KakaoLoginRequest(
        @Schema(description = "카카오 OAuth 인가 코드", example = "kakao-authorization-code")
        @NotBlank(message = "인가 코드는 필수입니다") String code) {
}
