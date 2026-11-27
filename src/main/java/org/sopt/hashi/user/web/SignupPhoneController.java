package org.sopt.hashi.user.web;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.dto.ConfirmSignupPhoneRequest;
import org.sopt.hashi.user.dto.IssueSignupPhoneChallengeRequest;
import org.sopt.hashi.user.dto.SignupPhoneChallengeResponse;
import org.sopt.hashi.user.dto.SignupPhoneConfirmationResponse;
import org.sopt.hashi.user.service.SignupPhoneService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/onboarding/phone")
public class SignupPhoneController {
    private final SignupPhoneService phone;

    public SignupPhoneController(SignupPhoneService phone) {
        this.phone = phone;
    }

    @PostMapping("/challenges")
    @ApiException(value = UserErrorCode.class, codes = {"PHONE_PROOF_LIMIT", "PHONE_PROOF_UNAVAILABLE"})
    public SuccessResponse<SignupPhoneChallengeResponse> issue(@Valid @RequestBody IssueSignupPhoneChallengeRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, phone.issue(request.phone()));
    }

    @PostMapping("/confirm")
    @ApiException(value = UserErrorCode.class, codes = {"PHONE_PROOF_REQUIRED", "PHONE_PROOF_LIMIT", "PHONE_PROOF_UNAVAILABLE"})
    public SuccessResponse<SignupPhoneConfirmationResponse> confirm(@Valid @RequestBody ConfirmSignupPhoneRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, new SignupPhoneConfirmationResponse(phone.confirm(request.phone(), request.challengeId())));
    }
}
