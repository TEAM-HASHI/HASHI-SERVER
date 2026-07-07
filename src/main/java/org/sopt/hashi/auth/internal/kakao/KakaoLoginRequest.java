package org.sopt.hashi.auth.internal.kakao;

import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(
        @NotBlank(message = "인가 코드는 필수입니다") String code) {
}
