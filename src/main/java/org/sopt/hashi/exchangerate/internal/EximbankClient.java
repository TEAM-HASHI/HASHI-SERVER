package org.sopt.hashi.exchangerate.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EximbankClient {
    static final String URL = "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON";
    private final ObjectMapper mapper;
    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public EximbankClient(ObjectMapper mapper, @Value("${hashi.exchange-rate.api-key:}") String key) {
        this.mapper = mapper;
        this.key = key;
    }

    public Optional<BigDecimal> fetch(LocalDate day) {
        if (key.isBlank()) {
            return Optional.empty();
        }
        URI uri = UriComponentsBuilder.fromUriString(URL).queryParam("authkey", "{key}")
                .queryParam("searchdate", day.format(DateTimeFormatter.BASIC_ISO_DATE))
                .queryParam("data", "AP01").encode().buildAndExpand(key).toUri();
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parse(mapper.readTree(response.body()));
            }
        } catch (IOException ignored) {
            // URL에 인증키가 있어 전송 예외나 응답 본문을 기록하지 않는다.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    static Optional<BigDecimal> parse(JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            return Optional.empty();
        }
        BigDecimal rate = null;
        for (JsonNode row : rows) {
            if (!"JPY(100)".equals(row.path("cur_unit").asText())) {
                continue;
            }
            String value = row.path("deal_bas_r").asText();
            if (rate != null || !row.path("result").isIntegralNumber() || row.path("result").intValue() != 1
                    || !value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:[.][0-9]{1,4})?")) {
                return Optional.empty();
            }
            rate = new BigDecimal(value.replace(",", ""));
            if (rate.signum() <= 0 || rate.precision() - rate.scale() > 8) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(rate);
    }
}
