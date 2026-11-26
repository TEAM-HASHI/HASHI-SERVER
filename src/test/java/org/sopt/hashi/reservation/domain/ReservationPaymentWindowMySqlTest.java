package org.sopt.hashi.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.reservation.ReservationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationPaymentWindowMySqlTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired ReservationRepository reservations;
    @Autowired TransactionTemplate transactions;

    @Test
    void 동시에_입금대기를_요청해도_양쪽이_같은_마감을_받는다() throws Exception {
        Long id = reservations.saveAndFlush(Reservation.anywhere(1L, "예약자", "식당", "도쿄",
                LocalDateTime.of(2027, 1, 1, 18, 0), 2, 0, 0, null, 0, Reservation.BASE_FEE)).getId();
        var start = new CyclicBarrier(2);
        Instant now = Instant.parse("2026-11-27T01:00:00.123456Z");
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(startPaymentWait(id, now, start));
            var second = workers.submit(startPaymentWait(id, now.plusSeconds(30), start));
            Instant deadline = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(deadline);
            Reservation saved = reservations.findById(id).orElseThrow();
            assertThat(saved.getPaymentDeadline()).isEqualTo(deadline);
            assertThat(saved.getPaymentWaitStartedAt().plusSeconds(86_400)).isEqualTo(deadline);
        } finally {
            workers.shutdownNow();
        }
    }

    private Callable<Instant> startPaymentWait(Long id, Instant now, CyclicBarrier start) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return transactions.execute(tx -> {
                Reservation booking = reservations.findForUpdate(id).orElseThrow();
                booking.changeStatusByAdmin(ReservationStatus.AWAITING_PAYMENT, now);
                return booking.getPaymentDeadline();
            });
        };
    }
}
