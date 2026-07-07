package org.sopt.hashi.auth.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 OAuth 연동. 인가코드를 카카오 토큰으로 교환한 뒤 사용자 kakaoId를 조회한다.
 * 카카오 4xx(만료·위조된 code)는 KAKAO_AUTH_FAILED(401), 5xx·통신 실패는 KAKAO_SERVER_ERROR(502)로 구분한다.
 */
@Component
public class KakaoOAuthClient {

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    // 카카오 무응답 시 요청 스레드가 무한 대기(→ 워커 고갈)하지 않도록 연결·응답 타임아웃을 명시한다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final KakaoProperties properties;

    public KakaoOAuthClient(KakaoProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public Long fetchKakaoId(String authorizationCode) {
        try {
            KakaoTokenResponse token = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenRequestBody(authorizationCode))
                    .retrieve()
                    .body(KakaoTokenResponse.class);
            if (token == null || token.accessToken() == null) {
                throw new BusinessException(AuthErrorCode.KAKAO_AUTH_FAILED);
            }

            KakaoUserResponse user = restClient.get()
                    .uri(USER_INFO_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(KakaoUserResponse.class);
            if (user == null || user.id() == null) {
                throw new BusinessException(AuthErrorCode.KAKAO_AUTH_FAILED);
            }
            return user.id();
        } catch (HttpClientErrorException e) {
            // 429(레이트리밋)는 인증 실패가 아니라 카카오 측 일시적 사정 — 서버 오류(502)로 구분한다.
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new BusinessException(AuthErrorCode.KAKAO_SERVER_ERROR, e);
            }
            // 그 외 4xx — 인가 코드가 잘못됐거나 만료됨
            throw new BusinessException(AuthErrorCode.KAKAO_AUTH_FAILED, e);
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패 등 카카오 측 문제 — 통신 불가(502)로 구분한다
            throw new BusinessException(AuthErrorCode.KAKAO_SERVER_ERROR, e);
        }
    }

    private MultiValueMap<String, String> tokenRequestBody(String authorizationCode) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", properties.clientId());
        body.add("redirect_uri", properties.redirectUri());
        body.add("code", authorizationCode);
        if (StringUtils.hasText(properties.clientSecret())) {
            body.add("client_secret", properties.clientSecret());
        }
        return body;
    }

    private record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    private record KakaoUserResponse(Long id) {
    }
}
