package org.sopt.hashi.exchangerate.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
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
            log.warn("환율 수집 실패: 서버에 은행 인증키가 설정되지 않았습니다. 조회일={}", day);
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
            log.warn("환율 수집 실패: 은행 HTTP 응답 오류. 조회일={}, 상태={}", day, response.statusCode());
        } catch (JsonProcessingException ignored) {
            log.warn("환율 수집 실패: 은행 응답을 해석할 수 없습니다. 조회일={}", day);
        } catch (IOException ignored) {
            log.warn("환율 수집 실패: 은행 연결 오류. 조회일={}", day);
        } catch (InterruptedException interrupted) {
            log.warn("환율 수집 중단. 조회일={}", day);
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    static Optional<BigDecimal> parse(JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            log.warn("환율 수집 실패: 은행 응답 형식 오류");
            return Optional.empty();
        }
        if (rows.isEmpty()) {
            log.info("당일 은행 고시가 아직 없습니다");
            return Optional.empty();
        }
        BigDecimal rate = null;
        for (JsonNode row : rows) {
            JsonNode result = row.path("result");
            if (!result.isInt() || result.intValue() != 1) {
                String reason = switch (result.asInt(-1)) {
                    case 2 -> "요청 매개변수 오류";
                    case 3 -> "인증 오류";
                    case 4 -> "일일 조회 한도 초과";
                    default -> "알 수 없는 결과 코드";
                };
                log.warn("환율 수집 실패: {}", reason);
                return Optional.empty();
            }
            if (!"JPY(100)".equals(row.path("cur_unit").asText())) {
                continue;
            }
            String value = row.path("deal_bas_r").asText();
            if (rate != null || !value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:[.][0-9]{1,4})?")) {
                log.warn("환율 수집 실패: 엔화 매매기준율 형식 오류");
                return Optional.empty();
            }
            rate = new BigDecimal(value.replace(",", ""));
            if (rate.signum() <= 0 || rate.precision() - rate.scale() > 8) {
                log.warn("환율 수집 실패: 엔화 매매기준율 범위 오류");
                return Optional.empty();
            }
        }
        if (rate == null) {
            log.warn("환율 수집 실패: 엔화 고시 항목 누락");
        }
        return Optional.ofNullable(rate);
    }
}
