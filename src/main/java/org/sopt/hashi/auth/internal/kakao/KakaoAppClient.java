package org.sopt.hashi.auth.internal.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** SDK 토큰을 클라이언트의 사용자 번호가 아니라 카카오에 직접 확인한다. */
@Component
public class KakaoAppClient {
    private final KakaoAppProperties properties;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public KakaoAppClient(KakaoAppProperties properties, RestClient.Builder builder) {
        this(properties, createClient(builder));
    }

    KakaoAppClient(KakaoAppProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    private static RestClient createClient(RestClient.Builder builder) {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(factory).build();
    }

    public Long verify(String accessToken) {
        if (properties.kakaoAppId() == null || properties.kakaoAppId() <= 0) {
            throw new BusinessException(AuthErrorCode.APP_AUTH_UNAVAILABLE);
        }
        try {
            TokenInfo info = client.get().uri("https://kapi.kakao.com/v1/user/access_token_info")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().body(TokenInfo.class);
            if (info == null || info.id() == null || info.id() <= 0 || info.expiresIn() == null
                    || info.expiresIn() <= 0 || !properties.kakaoAppId().equals(info.appId())) {
                throw new BusinessException(AuthErrorCode.KAKAO_AUTH_FAILED);
            }
            return info.id();
        } catch (HttpClientErrorException e) {
            throw new BusinessException(e.getStatusCode().value() == 429 || isTemporaryFailure(e)
                    ? AuthErrorCode.KAKAO_SERVER_ERROR : AuthErrorCode.KAKAO_AUTH_FAILED);
        } catch (RestClientException e) {
            // 제공자 응답/예외에 토큰이 포함될 수 있어 cause와 본문을 보관하지 않는다.
            throw new BusinessException(AuthErrorCode.KAKAO_SERVER_ERROR);
        }
    }

    private boolean isTemporaryFailure(HttpClientErrorException error) {
        try {
            KakaoError body = error.getResponseBodyAs(KakaoError.class);
            return body != null && Integer.valueOf(-1).equals(body.code());
        } catch (RestClientException | IllegalStateException ignored) {
            return false;
        }
    }

    record KakaoError(Integer code) { }

    record TokenInfo(Long id, @JsonProperty("app_id") Long appId,
                     @JsonProperty("expires_in") Long expiresIn) { }
}
