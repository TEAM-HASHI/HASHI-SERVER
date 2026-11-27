package org.sopt.hashi.exchangerate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ApplicationModuleTest
@Import(ExchangeRateMySqlTest.Clocks.class)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExchangeRateMySqlTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        Clock japanClock() {
            return Clock.systemUTC();
        }
    }

    @Container @ServiceConnection static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired ExchangeRateStore store;
    @Autowired JdbcTemplate db;
    @MockitoBean CurrentActorProvider actors;
    @MockitoBean RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void clearHistory() {
        db.update("DELETE FROM menu_exchange_revision");
        db.update("DELETE FROM menu_exchange_day");
    }

    @Test
    void 동시_수집은_하나만_선점하고_수동_보정은_늦은_자동_응답에도_유지된다() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 1);
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> claim = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return store.claim(day);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var manual = store.correct(day, day.minusDays(1), new BigDecimal("980"), 7L);
        store.completeLookup(day, Optional.of(new BigDecimal("999")));
        assertThat(store.latest(day)).contains(manual);
        assertThat(store.correct(day, day.minusDays(1), new BigDecimal("980.00"), 7L)).isEqualTo(manual);
        assertThat(store.claim(day)).isFalse();
        var revised = store.correct(day, day, new BigDecimal("970"), 8L);
        var page0 = store.history(PageRequest.of(0, 1));
        var page1 = store.history(PageRequest.of(1, 1));
        assertThat(page0.getContent()).containsExactly(revised);
        assertThat(page1.getContent()).containsExactly(manual);
        assertThat(page1.getTotalElements()).isEqualTo(2);
        assertThat(page1.getTotalPages()).isEqualTo(2);
        assertThat(store.history(PageRequest.of(Integer.MAX_VALUE, 100)).getContent()).isEmpty();
    }

    @Test
    void 실패와_휴일에는_고시일을_유지하고_재시도는_하루_세번으로_제한한다() {
        LocalDate first = LocalDate.of(2025, 1, 10);
        LocalDate holiday = first.plusDays(1);
        assertThat(store.latest(first)).isEmpty();
        store.claim(first);
        store.completeLookup(first, Optional.of(new BigDecimal("975.18")));
        store.completeLookup(first, Optional.of(new BigDecimal("987.00")));
        for (int i = 0; i < 3; i++) {
            assertThat(store.claim(holiday)).isTrue();
            store.completeLookup(holiday, Optional.empty());
            assertThat(store.claim(holiday)).isFalse();
            db.update("UPDATE menu_exchange_day SET retry_after=UTC_TIMESTAMP(6) WHERE applied_date=?", holiday);
        }
        assertThat(store.claim(holiday)).isFalse();
        var carried = store.latest(holiday).orElseThrow();
        assertThat(carried.quote().appliedDate()).isEqualTo(holiday);
        assertThat(carried.quote().quotedDate()).isEqualTo(first);
        assertThat(carried.quote().krwPer100Jpy()).isEqualByComparingTo("975.18");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM menu_exchange_revision WHERE applied_date=?", Integer.class, holiday))
                .isEqualTo(1);
    }
}
