package org.sopt.hashi.user.web;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.dto.FriendInviteResponse;
import org.sopt.hashi.user.dto.FriendInviteValidationResponse;
import org.sopt.hashi.user.dto.ValidateFriendInviteCodeRequest;
import org.sopt.hashi.user.service.FriendInviteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class FriendInviteController {
    private final FriendInviteService invites;

    public FriendInviteController(FriendInviteService invites) {
        this.invites = invites;
    }

    /** 내 친구 초대 코드와 공유 링크. 기존 회원도 처음 조회할 때 발급한다. */
    @GetMapping("/me/invite")
    @ApiException(value = UserErrorCode.class, codes = {"NOT_FOUND", "INVITE_CODE_UNAVAILABLE"})
    public SuccessResponse<FriendInviteResponse> getMine() {
        return SuccessResponse.of(CommonSuccessCode.OK, invites.getMine());
    }

    /** 온보딩 중 입력한 코드의 사용 가능 여부. 초대한 회원의 정보는 공개하지 않는다. */
    @PostMapping("/onboarding/invite-code/validate")
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    public SuccessResponse<FriendInviteValidationResponse> validate(@Valid @RequestBody ValidateFriendInviteCodeRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, new FriendInviteValidationResponse(invites.isValid(request.inviteCode())));
    }
}
