package org.sopt.hashi.user.web;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.code.UserSuccessCode;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.dto.OnboardingResponse;
import org.sopt.hashi.user.service.OnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원(users) 리소스 컨트롤러. 리소스 단위로 단일화하고, 엔드포인트가 늘면 클래스가 아니라 메서드를 추가한다.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final OnboardingService onboardingService;

    public UserController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * 온보딩(가입 완료). 인증은 Bearer가 아니라 로그인 시 발급된 signup_token HttpOnly 쿠키로 이뤄진다
     * (ROLE_ONBOARDING). 회원 저장과 동시에 auth가 소셜 계정을 연결하고, 성공 시 정식 JWT를 응답에 실어준다.
     */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class,
            codes = {"DUPLICATE_NICKNAME", "DUPLICATE_EMAIL", "DUPLICATE_PHONE", "DUPLICATE_USER_INFO"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/onboarding")
    public SuccessResponse<OnboardingResponse> completeOnboarding(
            @Valid @RequestBody CompleteOnboardingRequest request) {
        return SuccessResponse.of(UserSuccessCode.ONBOARDING_COMPLETED,
                onboardingService.completeOnboarding(request));
    }
}
