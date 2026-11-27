package org.sopt.hashi.user.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.code.UserErrorCode;
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
    public SuccessResponse<SignupPhoneService.Challenge> issue(@Valid @RequestBody Issue request) {
        return SuccessResponse.of(CommonSuccessCode.OK, phone.issue(request.phone()));
    }

    @PostMapping("/confirm")
    @ApiException(value = UserErrorCode.class, codes = {"PHONE_PROOF_REQUIRED", "PHONE_PROOF_LIMIT", "PHONE_PROOF_UNAVAILABLE"})
    public SuccessResponse<Result> confirm(@Valid @RequestBody Confirm request) {
        return SuccessResponse.of(CommonSuccessCode.OK, new Result(phone.confirm(request.phone(), request.challengeId())));
    }

    public record Issue(@NotNull @Pattern(regexp = "010[0-9]{8}") String phone) {}
    public record Confirm(@NotNull @Pattern(regexp = "010[0-9]{8}") String phone, @NotNull UUID challengeId) {}
    public record Result(boolean verified) {}
}
