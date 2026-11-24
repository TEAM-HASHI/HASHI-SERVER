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

    static final String EXECUTOR = "reservationNotificationExecutor";

    @Bean
    ReservationWebhookSender reservationWebhookSender(
            ObjectMapper objectMapper,
            @Value("${hashi.reservation.notifications.slack-webhook-url:}") String slackWebhook,
            @Value("${hashi.reservation.notifications.discord-webhook-url:}") String discordWebhook) {
        return new ReservationWebhookSender(objectMapper, slackWebhook, discordWebhook);
    }

    @Bean
    ReservationNotificationListener reservationNotificationListener(ReservationWebhookSender sender) {
        return new ReservationNotificationListener(sender);
    }

    @Bean(name = EXECUTOR)
    ThreadPoolTaskExecutor reservationNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("reservation-notification-");
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
