package org.sopt.hashi.reservation.internal.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.sopt.hashi.reservation.event.ReservationRequestedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@Profile("prod & !dev & !local")
@ConditionalOnProperty(name = "hashi.reservation.notifications.enabled", havingValue = "true")
class ReservationNotificationConfig {

    static final String SLACK_EXECUTOR = "reservationSlackExecutor";
    static final String DISCORD_EXECUTOR = "reservationDiscordExecutor";
    private static final int QUEUE_CAPACITY = 4;

    @Bean
    ReservationWebhookSender reservationWebhookSender(
            ObjectMapper objectMapper,
            @Qualifier("japanClock") Clock clock,
            @Value("${hashi.reservation.notifications.slack-webhook-url:}") String slackWebhook,
            @Value("${hashi.reservation.notifications.discord-webhook-url:}") String discordWebhook) {
        return new ReservationWebhookSender(objectMapper, clock, slackWebhook, discordWebhook);
    }

    @Bean
    ReservationNotificationListener reservationNotificationListener(ReservationWebhookSender sender) {
        return new ReservationNotificationListener(sender);
    }

    @Bean(name = SLACK_EXECUTOR)
    ThreadPoolTaskExecutor reservationSlackExecutor() {
        return notificationExecutor("reservation-slack-");
    }

    @Bean(name = DISCORD_EXECUTOR)
    ThreadPoolTaskExecutor reservationDiscordExecutor() {
        return notificationExecutor("reservation-discord-");
    }

    private ThreadPoolTaskExecutor notificationExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // 요청당 5초인 HTTP 작업은 최대 25초치만 대기시켜 60초 재제출 기준에 여유를 둔다.
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    ReservationNotificationRecovery reservationNotificationRecovery(
            IncompleteEventPublications publications, @Qualifier("japanClock") Clock clock) {
        return new ReservationNotificationRecovery(publications, clock);
    }

    record ReservationNotificationRecovery(IncompleteEventPublications publications, Clock clock) {

        @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
        public void resubmitFailedDeliveries() {
            Instant cutoff = clock.instant().minusSeconds(60);
            publications.resubmitIncompletePublications(publication ->
                    publication.getEvent() instanceof ReservationRequestedEvent
                            && !publication.getPublicationDate().isAfter(cutoff));
        }
    }
}
