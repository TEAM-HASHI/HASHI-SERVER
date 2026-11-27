package org.sopt.hashi.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.point.code.PointErrorCode;
import org.sopt.hashi.point.service.PointService;
import org.sopt.hashi.reservation.PaymentStatus;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.reservation.dto.RebookReservationRequest;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.user.UserPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ReservationService.class, TimeConfig.class, ReservationAlternativesMySqlTest.PointConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationAlternativesMySqlTest {

    @TestConfiguration
    @ComponentScan(basePackageClasses = PointService.class)
    static class PointConfig {}

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final LocalDateTime SIX = LocalDateTime.of(2090, 4, 2, 18, 0);
    private static final LocalDateTime SEVEN = SIX.plusHours(1);
    @Autowired ReservationService service;
    @Autowired ReservationRepository reservations;
    @Autowired PointPort points;
    @MockitoBean UserPort users;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean CurrentActorProvider actor;
    @MockitoBean MediaPort media;

    @BeforeEach
    void setUp() {
        given(actor.currentActor()).willReturn(new CurrentActor(ActorType.ADMIN, 1L));
        given(users.lockActiveUser(any())).willReturn(true);
    }

    @Test
    void 실패_복원과_재예약_차감은_재전송과_동시_요청에도_한번이다() throws Exception {
        long user = 1001L;
        given(currentUser.currentUserId()).willReturn(user);
        Reservation original = reservations.saveAndFlush(Reservation.anywhere(user, "예약자", "이자카야", "도쿄",
                SIX, 2, 1, 1, "창가", 2000, 2000));
        long id = original.getId();
        points.earn(user, 2000, "가입", PointSourceType.USER, user);
        points.use(user, 2000, "기존 예약", PointSourceType.RESERVATION, id);
        service.setAlternativesByAdmin(id, List.of(SEVEN), List.of());
        service.setAlternativesByAdmin(id, List.of(SEVEN), List.of());
        assertThat(points.getBalance(user)).isEqualTo(2000);
        assertThat(service.getMyAlternatives(id).alternatives().times()).containsExactly(SEVEN);
        points.use(user, 1500, "다른 예약", PointSourceType.RESERVATION, id + 10000);
        assertThatThrownBy(() -> service.rebook(id, new RebookReservationRequest(SEVEN, 2000L)))
                .hasFieldOrPropertyWithValue("errorCode", PointErrorCode.INSUFFICIENT_BALANCE);
        assertThat(reservations.findByOriginalReservationId(id)).isEmpty();
        assertThat(points.getBalance(user)).isEqualTo(500);

        var start = new CyclicBarrier(2);
        Callable<Long> request = () -> {
            start.await(10, TimeUnit.SECONDS);
            return service.rebook(id, new RebookReservationRequest(SEVEN, 500L)).reservationId();
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(points.getBalance(user)).isZero();
        var child = reservations.findByOriginalReservationId(id).orElseThrow();
        assertThat(child.getUsedPoint()).isEqualTo(500);
        assertThat(child.getAmount()).isEqualByComparingTo("3500");
        assertThat(child.getReservedAt()).isEqualTo(SEVEN);
        assertThat(child.getRequestNote()).isEqualTo("창가");
        assertThat(child.getRestaurantName()).isEqualTo("이자카야");
        assertThat(List.of(child.getAdultCount(), child.getTeenCount(), child.getChildCount()))
                .containsExactly(2, 1, 1);
        assertThat(reservations.findById(id).orElseThrow().getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(service.getMyAlternatives(id).alternatives().rebookedReservationId()).isEqualTo(child.getId());
        assertThatThrownBy(() -> service.rebook(id, new RebookReservationRequest(SEVEN, 0L)))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.REBOOKING_CONFLICT);
    }

    @Test
    void 대안_식당은_최대_세_곳까지_어드민이_등록한_순서와_카드_정보를_보여준다() {
        long owner = 1004L;
        long id = reservations.saveAndFlush(Reservation.anywhere(owner, "예약자", "원래 식당", "도쿄",
                SIX, 1, 0, 0, null, 0, 4000)).getId();
        given(currentUser.currentUserId()).willReturn(owner);
        var ids = List.of(3L, 2L, 1L);
        given(restaurants.countActiveByIds(ids)).willReturn(3L);
        given(restaurants.findActiveDetails(any())).willAnswer(call ->
                call.<List<Long>>getArgument(0).stream().map(restaurant -> new RestaurantDetailInfo(
                        restaurant, "대안 식당", null, null, "긴자", "교자", null, List.of("photo"),
                        new BigDecimal("4.8"), null, null)).toList());
        service.setAlternativesByAdmin(id, List.of(), ids);
        var cards = service.getMyAlternatives(id).alternatives().restaurants();
        assertThat(cards).extracting("restaurantId").containsExactly(3L, 2L, 1L);
        assertThat(cards.getFirst().rating()).isEqualByComparingTo("4.8");
        assertThat(cards.getFirst().foodCategory()).isEqualTo("교자");

        var reordered = List.of(1L, 3L, 2L);
        given(restaurants.countActiveByIds(reordered)).willReturn(3L);
        service.setAlternativesByAdmin(id, List.of(), reordered);
        assertThat(service.getAlternativesByAdmin(id).restaurants()).extracting("restaurantId")
                .containsExactly(1L, 3L, 2L);
        assertThatThrownBy(() -> service.cancel(id))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.CANNOT_CANCEL);
        assertThatThrownBy(() -> service.changeStatusByAdmin(id, ReservationStatus.CONFIRMED))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.INVALID_PAYMENT_TRANSITION);
    }

    @Test
    void 등록하지_않은_시간이나_삭제된_식당으로_재예약할_수_없고_타인은_접근할_수_없다() {
        long owner = 1002L;
        long id = reservations.saveAndFlush(Reservation.standard(owner, "예약자", 17L, SIX,
                2, 0, 0, null, 0, 4000)).getId();
        given(currentUser.currentUserId()).willReturn(owner);
        assertThatThrownBy(() -> service.setAlternativesByAdmin(id, List.of(SIX.plusMinutes(15)), List.of()))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.INVALID_ALTERNATIVES);
        assertThatThrownBy(() -> service.setAlternativesByAdmin(id, List.of(), List.of(1L, 2L, 3L, 4L)))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.INVALID_ALTERNATIVES);
        assertThatThrownBy(() -> service.setAlternativesByAdmin(id, List.of(), List.of(99L)))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.INVALID_ALTERNATIVES);
        assertThat(reservations.findById(id).orElseThrow().getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
        service.changeStatusByAdmin(id, ReservationStatus.FAILED);
        assertThat(service.getMyAlternatives(id).alternatives().times()).isEmpty();
        service.setAlternativesByAdmin(id, List.of(SEVEN), List.of());
        assertThatThrownBy(() -> service.rebook(id, new RebookReservationRequest(SIX.plusMinutes(30), 0L)))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.INVALID_ALTERNATIVES);
        assertThatThrownBy(() -> service.rebook(id, new RebookReservationRequest(SEVEN, 0L)))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.RESTAURANT_NOT_FOUND);
        given(currentUser.currentUserId()).willReturn(1003L);
        assertThatThrownBy(() -> service.getMyAlternatives(id))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.rebook(id, new RebookReservationRequest(SEVEN, 0L)))
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.NOT_FOUND);
        given(currentUser.currentUserId()).willReturn(owner);
        given(restaurants.existsActiveById(17L)).willReturn(true);
        assertThat(service.rebook(id, new RebookReservationRequest(SEVEN, 0L)).restaurantId()).isEqualTo(17L);
    }
}
