package org.sopt.hashi.reservation.internal.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.reservation.event.ReservationRequestedEvent;

@Slf4j
class ReservationWebhookSender implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter VISIT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    private static final Pattern CONTROL_OR_LINE_BREAK = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");
    private static final Pattern DISCORD_MARKDOWN = Pattern.compile("[\\\\`*_~|<>\\[\\]()#]");
    private static final String DISCORD_MARKDOWN_ESCAPE = "\\\\$0";
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
        send("Slack", slackUri, Map.of(
                "text", message(event, slackRestaurantName(event)),
                "mrkdwn", false,
                "unfurl_links", false,
                "unfurl_media", false));
    }

    void sendDiscord(ReservationRequestedEvent event) {
        send("Discord", discordUri, Map.of(
                "content", message(event, discordRestaurantName(event)),
                "allowed_mentions", Map.of("parse", List.of()),
                "flags", 4)); // SUPPRESS_EMBEDS
    }

    private static String message(ReservationRequestedEvent event, String restaurantName) {
        String type = switch (event.reservationType()) {
            case STANDARD -> "등록 식당";
            case ANYWHERE -> "어디든";
        };
        String title = "[HASHI 운영] 새 예약 요청 #%d (%s)".formatted(event.reservationId(), type);
        if (event.reservedAt() == null) {
            return title;
        }
        int total = event.adultCount() + event.teenCount() + event.childCount();
        return """
                %s
                예약자 %s
                식당 %s
                방문 예정 %s (일본 현지 시각)
                총 %d명 (성인 %d, 청소년 %d, 아동 %d)""".formatted(
                title, event.maskedReserverName(), restaurantName, VISIT_TIME_FORMATTER.format(event.reservedAt()),
                total, event.adultCount(), event.teenCount(), event.childCount());
    }

    private static String restaurantName(ReservationRequestedEvent event) {
        if (event.restaurantName() == null) {
            return "알 수 없는 식당";
        }
        return CONTROL_OR_LINE_BREAK.matcher(event.restaurantName()).replaceAll(" ").strip();
    }

    private static String slackRestaurantName(ReservationRequestedEvent event) {
        return restaurantName(event).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String discordRestaurantName(ReservationRequestedEvent event) {
        return DISCORD_MARKDOWN.matcher(restaurantName(event)).replaceAll(DISCORD_MARKDOWN_ESCAPE);
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
