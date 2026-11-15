package org.sopt.hashi.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ValidationSecretTest {
    void endpoint(Object request) { }

    @Test
    void 인증값은_검증_응답에서_제외하고_일반_필드값은_유지한다() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        for (String field : new String[]{"kakaoAccessToken", "refreshToken", "password", "code", "codeVerifier"}) {
            binding.addError(new FieldError("request", field, "must-not-leak", false, null, null, "invalid"));
        }
        binding.addError(new FieldError("request", "nickname", "bad-name", false, null, null, "invalid"));
        var parameter = new MethodParameter(getClass().getDeclaredMethod("endpoint", Object.class), 0);
        var result = new GlobalExceptionHandler().handleValidationException(
                new MethodArgumentNotValidException(parameter, binding), new MockHttpServletRequest());
        assertThat(result.getBody().errors().subList(0, 5)).allSatisfy(error ->
                assertThat(error.rejectedValue()).isNull());
        assertThat(result.getBody().errors().getLast().rejectedValue()).isEqualTo("bad-name");
    }
}
