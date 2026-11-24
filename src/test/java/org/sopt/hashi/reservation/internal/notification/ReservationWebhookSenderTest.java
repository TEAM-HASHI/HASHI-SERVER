package org.sopt.hashi.reservation.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.event.ReservationRequestedEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReservationWebhookSenderTest {

    private static final String SLACK_URL = "https://hooks.slack.com/services/test-team/test-app/test-token";
    private static final String DISCORD_URL = "https://discord.com/api/webhooks/123/test-token";
    private static final ReservationRequestedEvent EVENT = new ReservationRequestedEvent(123L, ReservationType.ANYWHERE);

    @Test
    void 두_제공자에_예약_번호와_유형만_전송한다() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper());
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock();
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        var sender = new ReservationWebhookSender(mapper, SLACK_URL, DISCORD_URL, client);

        sender.sendSlack(EVENT);
        sender.sendDiscord(EVENT);

        String message = "[HASHI 운영] 새 예약 요청 #123 (어디든)";
        verify(mapper).writeValueAsBytes(Map.of("text", message));
        verify(mapper).writeValueAsBytes(Map.of("content", message, "allowed_mentions", Map.of("parse", List.of())));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, org.mockito.Mockito.times(2)).send(
                requests.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertThat(requests.getAllValues()).extracting(request -> request.uri().toString())
                .containsExactly(SLACK_URL, DISCORD_URL + "?wait=true");
        assertThat(requests.getAllValues()).extracting(HttpRequest::method).containsOnly("POST");
    }

    @Test
    void 공급자_연결_예외의_웹훅_URL은_전파하지_않는다() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenThrow(new IOException("Failed to call " + SLACK_URL));
        var sender = new ReservationWebhookSender(new ObjectMapper(), SLACK_URL, DISCORD_URL, client);

        assertThatThrownBy(() -> sender.sendSlack(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Slack 예약 알림 전송 실패. errorType=IOException")
                .hasNoCause();
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
