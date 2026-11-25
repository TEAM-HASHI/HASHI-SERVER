package org.sopt.hashi.reservation.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.dto.CreateAnywhereReservationRequest;
import org.sopt.hashi.reservation.dto.CreateReservationRequest;
import org.sopt.hashi.reservation.event.ReservationRequestedEvent;
import org.sopt.hashi.reservation.service.ReservationService;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.user.UserPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationModuleTest
@Import(ReservationNotificationIntegrationTest.Infrastructure.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:reservation-notifications;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.reservation.notifications.enabled=true"
})
@ActiveProfiles("prod")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationNotificationIntegrationTest {

    private static final LocalDateTime VISIT_TIME = LocalDateTime.of(2030, 8, 1, 19, 0);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAsync
    @EnableJpaAuditing
    static class Infrastructure {

        @Bean("japanClock")
        Clock japanClock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationNotificationConfig.ReservationNotificationRecovery recovery;

    @Autowired
    @Qualifier(ReservationNotificationConfig.SLACK_EXECUTOR)
    private ThreadPoolTaskExecutor slackExecutor;

    @Autowired
    @Qualifier(ReservationNotificationConfig.DISCORD_EXECUTOR)
    private ThreadPoolTaskExecutor discordExecutor;

    @MockitoBean
    private ReservationWebhookSender sender;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    @MockitoBean
    private RestaurantPort restaurantPort;

    @MockitoBean
    private MediaPort mediaPort;

    @MockitoBean
    private PointPort pointPort;

    @MockitoBean
    private UserPort userPort;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void 등록_식당_예약은_커밋_뒤_두_채널에_전송하고_실패한_채널만_재시도한다() {
        when(currentUserProvider.currentUserId()).thenReturn(7L);
        when(userPort.lockActiveUser(7L)).thenReturn(true);
        when(restaurantPort.existsById(10L)).thenReturn(true);
        when(restaurantPort.findSummaryById(10L))
                .thenReturn(Optional.of(new RestaurantInfo(10L, "테스트 스시", "테스트 주소", null)));

        ReservationRequestedEvent event = transactions.execute(status -> {
            var response = reservationService.create(new CreateReservationRequest(
                    "테스트 예약자", 10L, VISIT_TIME, 2, 0, 1, "테스트 요청사항", 0L, 4_000L));
            var requested = new ReservationRequestedEvent(response.reservationId(), ReservationType.STANDARD,
                    "테**", "테스트 스시", VISIT_TIME, 2, 0, 1);
            doThrow(new IllegalStateException("Discord 일시 실패")).doNothing().when(sender).sendDiscord(requested);
            verify(sender, never()).sendSlack(requested);
            verify(sender, never()).sendDiscord(requested);
            return requested;
        });

        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
            verify(sender).sendSlack(event);
            verify(sender).sendDiscord(event);
            assertThat(pendingListenerIds()).containsExactly("reservation-requested-discord-v1");
            assertThat(discordExecutor.getActiveCount()).isZero();
        });
        String storedEvent = jdbcTemplate.queryForObject("SELECT serialized_event FROM event_publication", String.class);
        assertThat(storedEvent).contains("테**", "테스트 스시")
                .doesNotContain("테스트 예약자", "테스트 요청사항", "테스트 주소");
        assertThat(event.toString()).doesNotContain("테**", "테스트 스시");

        resubmitPendingDeliveries();

        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
            verify(sender, times(2)).sendDiscord(event);
            verify(sender).sendSlack(event);
            assertThat(pendingListenerIds()).isEmpty();
        });
    }

    @Test
    void 어디든_예약은_롤백하면_전송하지_않고_커밋하면_두_채널에_전송한다() {
        when(currentUserProvider.currentUserId()).thenReturn(7L);
        when(userPort.lockActiveUser(7L)).thenReturn(true);
        var request = anywhereRequest();

        ReservationRequestedEvent rolledBack = transactions.execute(status -> {
            var response = reservationService.createAnywhere(request);
            status.setRollbackOnly();
            return anywhereEvent(response.reservationId());
        });
        assertThat(pendingListenerIds()).isEmpty();
        var response = reservationService.createAnywhere(request);
        var committed = anywhereEvent(response.reservationId());

        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
            verify(sender).sendSlack(committed);
            verify(sender).sendDiscord(committed);
            assertThat(pendingListenerIds()).isEmpty();
        });
        verify(sender, never()).sendSlack(rolledBack);
        verify(sender, never()).sendDiscord(rolledBack);
    }

    @Test
    void 슬랙_큐가_가득_차도_예약은_성공하고_슬랙만_나중에_재전송한다() throws InterruptedException {
        when(currentUserProvider.currentUserId()).thenReturn(7L);
        when(userPort.lockActiveUser(7L)).thenReturn(true);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        slackExecutor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ReservationRequestedEvent event;
        try {
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < slackExecutor.getQueueCapacity(); i++) {
                slackExecutor.execute(() -> {});
            }
            var response = reservationService.createAnywhere(anywhereRequest());
            event = anywhereEvent(response.reservationId());

            await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
                verify(sender).sendDiscord(event);
                verify(sender, never()).sendSlack(event);
                assertThat(pendingListenerIds()).containsExactly("reservation-requested-slack-v1");
            });
        } finally {
            releaseWorker.countDown();
        }

        await().atMost(DELIVERY_TIMEOUT).until(
                () -> slackExecutor.getActiveCount() == 0 && slackExecutor.getQueueSize() == 0);
        resubmitPendingDeliveries();

        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
            verify(sender).sendSlack(event);
            verify(sender).sendDiscord(event);
            assertThat(pendingListenerIds()).isEmpty();
        });
    }

    private void resubmitPendingDeliveries() {
        jdbcTemplate.update("UPDATE event_publication SET publication_date = ?",
                Timestamp.from(Instant.now().minusSeconds(90)));
        recovery.resubmitFailedDeliveries();
    }

    private List<String> pendingListenerIds() {
        return jdbcTemplate.queryForList("SELECT listener_id FROM event_publication", String.class);
    }

    private ReservationRequestedEvent anywhereEvent(long reservationId) {
        return new ReservationRequestedEvent(reservationId, ReservationType.ANYWHERE,
                "***", "테스트 식당", VISIT_TIME, 2, 1, 0);
    }

    private CreateAnywhereReservationRequest anywhereRequest() {
        return new CreateAnywhereReservationRequest(
                "김", "테스트 식당", "테스트 주소", VISIT_TIME, 2, 1, 0, null, 0L, 4_000L);
    }
}
