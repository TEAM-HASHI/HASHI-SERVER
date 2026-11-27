package org.sopt.hashi.user.internal.phone;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OctomoClient {
    static final String URL = "https://api.octoverse.kr/octomo/v1/public/message/exists";
    private final RestClient client;
    private final String apiKey;

    @Autowired
    public OctomoClient(@Value("${hashi.signup-phone.api-key:}") String apiKey) {
        this(apiKey, createClient());
    }

    OctomoClient(String apiKey, RestClient client) {
        this.apiKey = apiKey;
        this.client = client;
    }

    private static RestClient createClient() {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean exists(String mobile, String text) {
        if (apiKey.isBlank()) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_UNAVAILABLE);
        }
        try {
            var response = client.post().uri(URL)
                    .header(HttpHeaders.AUTHORIZATION, "Octomo " + apiKey)
                    .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mobileNum", mobile, "text", text, "withinMinutes", 5))
                    .retrieve().toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (response.getStatusCode().value() == 201 && body != null && body.path("exists").isBoolean()) {
                return body.get("exists").booleanValue();
            }
        } catch (RestClientException ignored) {
            // 제공자 오류 응답에는 원래 요청이 포함될 수 있어 예외 본문은 전파하지 않는다.
        }
        throw new BusinessException(UserErrorCode.PHONE_PROOF_UNAVAILABLE);
    }
}
