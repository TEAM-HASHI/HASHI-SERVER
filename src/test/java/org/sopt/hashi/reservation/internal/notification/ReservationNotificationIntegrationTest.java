package org.sopt.hashi.reservation.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.user.UserPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
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

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationNotificationConfig.ReservationNotificationRecovery recovery;

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

    @Test
    void 등록_식당_예약은_커밋_뒤_두_채널에_전송하고_실패한_채널만_재시도한다() {
        when(currentUserProvider.currentUserId()).thenReturn(7L);
        when(userPort.lockActiveUser(7L)).thenReturn(true);
        when(restaurantPort.existsById(10L)).thenReturn(true);

        ReservationRequestedEvent event = transactions.execute(status -> {
            var response = reservationService.create(new CreateReservationRequest(
                    "테스트 예약자", 10L, VISIT_TIME, 2, 0, 0, null, 0L, 4_000L));
            var requested = new ReservationRequestedEvent(response.reservationId(), ReservationType.STANDARD);
            doThrow(new IllegalStateException("Discord 일시 실패")).doNothing().when(sender).sendDiscord(requested);
            verify(sender, never()).sendSlack(requested);
            verify(sender, never()).sendDiscord(requested);
            return requested;
        });

        verify(sender, after(3_000)).sendSlack(event);
        verify(sender, times(1)).sendDiscord(event);
        assertThat(pendingListenerIds()).containsExactly("reservation-requested-discord-v1");

        jdbcTemplate.update("UPDATE event_publication SET publication_date = ?",
                Timestamp.from(Instant.now().minusSeconds(90)));
        recovery.resubmitFailedDeliveries();

        verify(sender, after(3_000).times(2)).sendDiscord(event);
        verify(sender, times(1)).sendSlack(event);
        assertThat(pendingListenerIds()).isEmpty();
    }

    @Test
    void 어디든_예약은_롤백하면_전송하지_않고_커밋하면_두_채널에_전송한다() {
        when(currentUserProvider.currentUserId()).thenReturn(7L);
        when(userPort.lockActiveUser(7L)).thenReturn(true);
        var request = new CreateAnywhereReservationRequest(
                "테스트 예약자", "테스트 식당", "테스트 주소", VISIT_TIME, 2, 0, 0, null, 0L, 4_000L);

        ReservationRequestedEvent rolledBack = transactions.execute(status -> {
            var response = reservationService.createAnywhere(request);
            status.setRollbackOnly();
            return new ReservationRequestedEvent(response.reservationId(), ReservationType.ANYWHERE);
        });
        assertThat(pendingListenerIds()).isEmpty();
        var response = reservationService.createAnywhere(request);
        var committed = new ReservationRequestedEvent(response.reservationId(), ReservationType.ANYWHERE);

        verify(sender, after(3_000)).sendSlack(committed);
        verify(sender).sendDiscord(committed);
        verify(sender, never()).sendSlack(rolledBack);
        verify(sender, never()).sendDiscord(rolledBack);
        assertThat(pendingListenerIds()).isEmpty();
    }

    private List<String> pendingListenerIds() {
        return jdbcTemplate.queryForList("SELECT listener_id FROM event_publication", String.class);
    }
}
