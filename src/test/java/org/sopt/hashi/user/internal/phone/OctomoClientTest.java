package org.sopt.hashi.user.internal.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OctomoClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
    private final OctomoClient provider = new OctomoClient("fixture", builder.build());

    @Test
    void 세_필드만_전송하고_201_불리언을_읽는다() {
        mock.expect(requestTo(OctomoClient.URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Octomo fixture"))
                .andExpect(content().json("""
                    {"mobileNum":"01000000000","text":"HASHI test","withinMinutes":5}
                    """, JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"exists\":true}"));
        assertThat(provider.exists("01000000000", "HASHI test")).isTrue();
        mock.verify();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"200|{\"exists\":true}", "201|{\"exists\":\"true\"}", "201|{}",
            "429|{}", "503|{}"})
    void 다른_상태나_잘못된_형식은_인증_성공으로_해석하지_않는다(int status, String json) {
        mock.expect(requestTo(OctomoClient.URL)).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body(json));
        assertThatThrownBy(() -> provider.exists("01000000000", "HASHI test"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.PHONE_PROOF_UNAVAILABLE);
    }
}
