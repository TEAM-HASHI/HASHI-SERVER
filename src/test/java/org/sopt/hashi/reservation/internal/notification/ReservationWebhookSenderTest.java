package org.sopt.hashi.reservation.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.event.ReservationRequestedEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReservationWebhookSenderTest {

    private static final String SLACK_URL = "https://hooks.slack.com/services/test-team/test-app/test-token";
    private static final String DISCORD_URL = "https://discord.com/api/webhooks/123/test-token";
    private static final ReservationRequestedEvent EVENT = new ReservationRequestedEvent(123L, ReservationType.ANYWHERE,
            "김**", "스시 A & B\n<@123> *별관*", LocalDateTime.of(2030, 8, 1, 19, 0),
            2, 0, 1);

    @Test
    void 두_제공자에_마스킹한_예약_상세와_멘션_방지_설정을_전송한다() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper());
        HttpClient client = mock(HttpClient.class);
        var response = response(200, Map.of());
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(response);
        var sender = new ReservationWebhookSender(mapper, Clock.systemUTC(), SLACK_URL, DISCORD_URL, client);

        sender.sendSlack(EVENT);
        sender.sendDiscord(EVENT);

        String message = """
                [HASHI 운영] 새 예약 요청 #123 (어디든)
                예약자 김**
                식당 %s
                방문 예정 2030-08-01 19:00 (일본 현지 시각)
                총 3명 (성인 2, 청소년 0, 아동 1)""";
        verify(mapper).writeValueAsBytes(Map.of(
                "text", message.formatted("스시 A &amp; B &lt;@123&gt; *별관*"),
                "mrkdwn", false, "unfurl_links", false, "unfurl_media", false));
        verify(mapper).writeValueAsBytes(Map.of(
                "content", message.formatted("스시 A & B \\<@123\\> \\*별관\\*"),
                "allowed_mentions", Map.of("parse", List.of()), "flags", 4));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(
                requests.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(requests.getAllValues()).extracting(request -> request.uri().toString())
                .containsExactly(SLACK_URL, DISCORD_URL + "?wait=true");
        assertThat(requests.getAllValues()).extracting(HttpRequest::method).containsOnly("POST");
        verify(response.body(), times(2)).close();
        verify(response.body(), never()).read();
    }

    @Test
    void 공급자_연결_예외의_웹훅_URL은_전파하지_않는다() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new IOException("Failed to call " + SLACK_URL));
        var sender = new ReservationWebhookSender(new ObjectMapper(), Clock.systemUTC(), SLACK_URL, DISCORD_URL, client);

        assertThatThrownBy(() -> sender.sendSlack(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Slack 예약 알림 전송 실패. reservationId=123, errorType=IOException")
                .hasNoCause();
    }

    @Test
    void 호출_제한은_Retry_After까지_해당_채널만_보류한다() throws Exception {
        HttpClient client = mock(HttpClient.class);
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2030-08-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var rateLimited = response(429, Map.of("Retry-After", List.of("65.5")));
        var delivered = response(200, Map.of());
        when(client.send(argThat(request -> request != null && request.uri().equals(URI.create(SLACK_URL))),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(rateLimited).thenReturn(delivered);
        when(client.send(argThat(request -> request != null && request.uri().getHost().equals("discord.com")),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(delivered);
        var sender = new ReservationWebhookSender(new ObjectMapper(), clock, SLACK_URL, DISCORD_URL, client);

        assertThatThrownBy(() -> sender.sendSlack(EVENT)).hasMessageContaining("status=429");
        sender.sendDiscord(EVENT);
        when(clock.instant()).thenReturn(now.plusSeconds(65));
        assertThatThrownBy(() -> sender.sendSlack(EVENT)).hasMessageContaining("보류");
        when(clock.instant()).thenReturn(now.plusSeconds(66));
        sender.sendSlack(EVENT);

        verify(client, times(3)).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void 삭제된_디스코드_웹훅은_같은_프로세스에서_다시_호출하지_않는다() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var deletedWebhook = response(404, Map.of());
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(deletedWebhook);
        var sender = new ReservationWebhookSender(new ObjectMapper(), Clock.systemUTC(), SLACK_URL, DISCORD_URL, client);

        assertThatThrownBy(() -> sender.sendDiscord(EVENT)).hasMessageContaining("status=404");
        assertThatThrownBy(() -> sender.sendDiscord(EVENT)).hasMessageContaining("보류");

        verify(client).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 410})
    void 슬랙_설정_오류가_난_채널만_후속_외부_호출을_보류한다(int status) throws Exception {
        HttpClient client = mock(HttpClient.class);
        var rejected = response(status, Map.of());
        var delivered = response(200, Map.of());
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(rejected).thenReturn(delivered);
        var sender = new ReservationWebhookSender(new ObjectMapper(), Clock.systemUTC(), SLACK_URL, DISCORD_URL, client);

        assertThatThrownBy(() -> sender.sendSlack(EVENT))
                .hasMessageContaining("reservationId=123")
                .hasMessageContaining("status=" + status);
        assertThatThrownBy(() -> sender.sendSlack(EVENT)).hasMessageContaining("보류");
        sender.sendDiscord(EVENT);

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(
                requests.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(requests.getAllValues()).extracting(request -> request.uri().getHost())
                .containsExactly("hooks.slack.com", "discord.com");
    }

    private HttpResponse<InputStream> response(int status, Map<String, List<String>> headers) {
        HttpResponse<InputStream> response = mock();
        InputStream body = mock(InputStream.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    @Test
    void 개발이나_로컬_프로필이_활성화되거나_기능이_꺼져_있으면_알림을_등록하지_않는다() {
        var context = new ApplicationContextRunner().withUserConfiguration(ReservationNotificationConfig.class);
        for (String profiles : List.of("dev", "local", "prod,dev", "prod,local")) {
            context.withPropertyValues("spring.profiles.active=" + profiles,
                            "hashi.reservation.notifications.enabled=true")
                    .run(result -> assertThat(result).doesNotHaveBean(ReservationNotificationListener.class));
        }
        context.withPropertyValues("spring.profiles.active=prod")
                .run(result -> assertThat(result).doesNotHaveBean(ReservationNotificationListener.class));
    }
}
