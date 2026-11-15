package org.sopt.hashi.auth.internal.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoAppClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KakaoAppClient client = new KakaoAppClient(new KakaoAppProperties(123L), builder.build());
    private static final String URL = "https://kapi.kakao.com/v1/user/access_token_info";

    @Test
    void 발급_앱과_만료를_검증하고_카카오_회원번호를_반환한다() {
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer sdk-token"))
                .andRespond(withSuccess("{\"id\":7,\"app_id\":123,\"expires_in\":100}", MediaType.APPLICATION_JSON));
        assertThat(client.verify("sdk-token")).isEqualTo(7L);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":7,\"app_id\":999,\"expires_in\":100}",
            "{\"id\":7,\"app_id\":123,\"expires_in\":0}",
            "{\"id\":0,\"app_id\":123,\"expires_in\":100}", "{}"})
    void 잘못된_앱_만료_회원번호를_거절한다(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verify("sdk-token")).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.KAKAO_AUTH_FAILED);
    }

    @Test
    void 앱_ID_미설정은_외부_호출없이_앱_로그인만_거절한다() {
        var unconfigured = new KakaoAppClient(new KakaoAppProperties(null), builder.build());
        assertThatThrownBy(() -> unconfigured.verify("sdk-token")).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.APP_AUTH_UNAVAILABLE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void 제공자_장애는_본문을_노출하지_않는다(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)).body("secret-token"));
        assertThatThrownBy(() -> client.verify("sdk-token")).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.KAKAO_SERVER_ERROR)
                .hasNoCause().hasMessageNotContaining("secret-token");
    }

    @Test
    void 카카오의_400_임시_장애도_서버_오류로_구분한다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":-1,\"msg\":\"temporary failure\"}"));
        assertThatThrownBy(() -> client.verify("sdk-token")).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.KAKAO_SERVER_ERROR);
    }

    @Test
    void 잘못된_카카오_토큰은_인증_오류다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.verify("sdk-token")).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.KAKAO_AUTH_FAILED);
    }
}
