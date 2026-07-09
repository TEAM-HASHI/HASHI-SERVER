package org.sopt.hashi.user.web;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.code.UserSuccessCode;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.dto.MyInfoResponse;
import org.sopt.hashi.user.dto.OnboardingResponse;
import org.sopt.hashi.user.dto.ProfileSummaryResponse;
import org.sopt.hashi.user.service.OnboardingService;
import org.sopt.hashi.user.service.UserProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final UserProfileService userProfileService;

    public UserController(OnboardingService onboardingService,
                          UserProfileService userProfileService) {
        this.onboardingService = onboardingService;
        this.userProfileService = userProfileService;
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

    /** 내 정보 조회(내 정보 수정 페이지용) — 온보딩에서 받은 프로필 전체. 프로필 사진 미등록이면 URL null. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/me")
    public SuccessResponse<MyInfoResponse> getMyInfo() {
        return SuccessResponse.of(CommonSuccessCode.OK, userProfileService.getMyInfo());
    }

    /** 프로필 요약(헤더·마이페이지용) — 닉네임 + 프로필 사진 URL만. */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"NOT_FOUND"})
    @GetMapping("/me/profile-summary")
    public SuccessResponse<ProfileSummaryResponse> getMyProfileSummary() {
        return SuccessResponse.of(CommonSuccessCode.OK, userProfileService.getMyProfileSummary());
    }
}
