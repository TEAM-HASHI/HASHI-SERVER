package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;

class SignupPhoneRolloutTest {
    @Test
    void 비활성_기간은_코드_없는_기존_가입만_허용한다() {
        var policy = new SignupPhonePolicy(false, "", "");
        var service = new SignupPhoneService(policy, null, null, null, null);
        assertThat(service.consumeForSignup("01000000000", false)).isNull();
        assertThatThrownBy(() -> service.consumeForSignup("01000000000", true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVITE_REWARD_UNAVAILABLE);
    }
}
