package org.sopt.hashi.auth.dev;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 개발용 테스트 토큰 발급 API — local·dev 프로필에서만 존재한다(운영은 404). refresh는 발급하지 않는다. */
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v1/auth/dev")
public class DevTokenController {

    private final DevTokenService devTokenService;

    public DevTokenController(DevTokenService devTokenService) {
        this.devTokenService = devTokenService;
    }

    /** 역할별 테스트 토큰 발급 — 응답의 accessToken을 우상단 Authorize에 붙여 쓴다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @PostMapping("/tokens")
    public SuccessResponse<DevTokenResponse> issueToken(@Valid @RequestBody IssueDevTokenRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                devTokenService.issue(request.role(), request.resolvedSubjectId()));
    }
}
