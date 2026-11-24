package org.sopt.hashi.reservation.internal.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.reservation.event.ReservationRequestedEvent;

@Slf4j
class ReservationWebhookSender implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SLACK_URL = Pattern.compile(
            "https://hooks\\.slack\\.com/services/(?:[A-Za-z0-9_-]+/){2}[A-Za-z0-9_-]+");
    private static final Pattern DISCORD_URL = Pattern.compile(
            "https://discord\\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]+");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI slackUri;
    private final URI discordUri;

    ReservationWebhookSender(ObjectMapper objectMapper, String slackWebhook, String discordWebhook) {
        this(objectMapper, slackWebhook, discordWebhook, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ReservationWebhookSender(ObjectMapper objectMapper, String slackWebhook, String discordWebhook,
                             HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.slackUri = webhookUri(slackWebhook, SLACK_URL, "Slack", "");
        this.discordUri = webhookUri(discordWebhook, DISCORD_URL, "Discord", "?wait=true");
        this.httpClient = httpClient;
    }

    void sendSlack(ReservationRequestedEvent event) {
        send("Slack", slackUri, Map.of("text", message(event)));
    }

    void sendDiscord(ReservationRequestedEvent event) {
        send("Discord", discordUri, Map.of(
                "content", message(event),
                "allowed_mentions", Map.of("parse", List.of())));
    }

    private static String message(ReservationRequestedEvent event) {
        String type = switch (event.reservationType()) {
            case STANDARD -> "등록 식당";
            case ANYWHERE -> "어디든";
        };
        return "[HASHI 운영] 새 예약 요청 #%d (%s)".formatted(event.reservationId(), type);
    }

    private void send(String provider, URI uri, Map<String, Object> payload) {
        if (uri == null) {
            throw new IllegalStateException(provider + " 운영 예약 웹훅 URL을 사용할 수 없습니다");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(serialize(payload)))
                .build();
        try {
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(provider + " 예약 알림 전송 실패. status=" + status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(provider + " 예약 알림 전송 중단");
        } catch (IOException e) {
            throw new IllegalStateException(provider + " 예약 알림 전송 실패. errorType="
                    + e.getClass().getSimpleName());
        }
    }

    private byte[] serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("예약 알림 메시지 직렬화 실패");
        }
    }

    private static URI webhookUri(String value, Pattern pattern, String provider, String query) {
        String url = value.strip();
        if (!pattern.matcher(url).matches()) {
            log.warn("{} 운영 예약 웹훅 URL 형식이 올바르지 않습니다", provider);
            return null;
        }
        return URI.create(url + query);
    }

    @Override
    public void close() {
        httpClient.shutdownNow();
    }
}
