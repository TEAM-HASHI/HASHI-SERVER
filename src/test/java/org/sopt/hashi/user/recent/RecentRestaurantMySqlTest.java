package org.sopt.hashi.user.recent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.sopt.hashi.restaurant.web.RestaurantController;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.recent.domain.RecentRestaurant;
import org.sopt.hashi.user.recent.domain.RecentRestaurantRepository;
import org.sopt.hashi.user.recent.dto.RecentRestaurantListResponse.Card;
import org.sopt.hashi.user.recent.dto.RecentRestaurantListResponse;
import org.sopt.hashi.user.recent.dto.RecentRestaurantRequest.Visit;
import org.sopt.hashi.user.recent.dto.RecentRestaurantRequest;
import org.sopt.hashi.user.recent.event.RestaurantViewedListener;
import org.sopt.hashi.user.recent.service.RecentRestaurantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@Import({RecentRestaurantService.class, RestaurantViewedListener.class, RecentRestaurantMySqlTest.ClockConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecentRestaurantMySqlTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00.000123Z");
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock japanClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired RecentRestaurantRepository history;
    @Autowired RecentRestaurantService service;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEventPublisher events;
    @MockitoBean RestaurantService summaries;
    private RestaurantController detail;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean CurrentUserProvider currentUser;
    private final Set<Long> deleted = new HashSet<>();
    private Long userId;

    @BeforeEach
    void setUp() {
        detail = new RestaurantController(summaries, events);
        String key = UUID.randomUUID().toString().substring(0, 8);
        userId = users.saveAndFlush(User.onboard(key, "USER", LocalDate.of(2000, 1, 1),
                key, key + "@example.org", null)).getId();
        given(currentUser.currentUserId()).willReturn(userId);
        given(restaurants.findActiveIds(any())).willAnswer(call -> call.<Collection<Long>>getArgument(0)
                .stream().filter(id -> !deleted.contains(id)).toList());
        given(restaurants.findActiveDetails(any())).willAnswer(call -> call.<Collection<Long>>getArgument(0)
                .stream().map(id -> new RestaurantDetailInfo(id, "식당", null, null, null, null,
                        null, List.of(), null, null, null, 17L)).toList());
    }

    @Test
    void 기기_이력_재전송과_탈퇴_후_상세_재요청을_처리한다() {
        detail.getRestaurantSummary(10L, HttpMethod.GET);
        assertThat(history.findByUserId(userId)).isEmpty();
        given(currentUser.isAuthenticatedUser()).willReturn(true);
        detail.getRestaurantSummary(10L, HttpMethod.GET);
        detail.getRestaurantSummary(999L, HttpMethod.HEAD);
        given(summaries.getRestaurantSummary(555L)).willThrow(new BusinessException(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> detail.getRestaurantSummary(555L, HttpMethod.GET)).isInstanceOf(BusinessException.class);
        detail.getRestaurantSummary(20L, HttpMethod.GET);
        detail.getRestaurantSummary(10L, HttpMethod.GET);
        var merge = new RecentRestaurantRequest(List.of(new Visit(11L, NOW.minusSeconds(3)),
                new Visit(12L, null), new Visit(13L, null), new Visit(10L, NOW.minusSeconds(60))));
        service.merge(merge);
        service.merge(merge);
        assertThat(service.list(null).content()).extracting(Card::restaurantId).containsExactly(10L, 20L, 11L, 12L, 13L);
        transactions.executeWithoutResult(tx -> {
            users.findByIdForUpdate(userId).orElseThrow().withdraw();
            history.deleteByUserId(userId);
        });
        detail.getRestaurantSummary(12L, HttpMethod.GET);
        assertThat(history.findByUserId(userId)).isEmpty();
    }

    @Test
    void 빠른_기기_시각을_포함한_병합은_재전송해도_정렬이_같다() {
        var visits = new RecentRestaurantRequest(List.of(new Visit(1L, NOW),
                new Visit(2L, NOW.plusSeconds(60)), new Visit(3L, NOW.plusSeconds(301))));
        service.merge(visits);
        service.merge(visits);
        assertThat(service.list(null).content()).extracting(Card::restaurantId).containsExactly(2L, 1L, 3L);
        assertThat(history.findByUserId(userId)).filteredOn(row -> row.getRestaurantId().equals(3L))
                .extracting(RecentRestaurant::getViewedAt).containsOnlyNulls();
    }

    @Test
    void 이력_트랜잭션이_실패해도_공개_상세_응답을_반환한다() {
        given(currentUser.isAuthenticatedUser()).willReturn(true);
        willThrow(new DataAccessResourceFailureException("history")).given(restaurants).findActiveIds(any());
        var summary = mock(RestaurantMainResponse.class);
        given(summaries.getRestaurantSummary(10L)).willReturn(summary);
        assertThat(detail.getRestaurantSummary(10L, HttpMethod.GET).data()).isSameAs(summary);
        assertThat(history.findByUserId(userId)).isEmpty();
    }

    @Test
    void 동시_병합에서도_최신_50곳을_남기고_삭제된_식당을_제외해_10개씩_이어본다() throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> mergeRange(1, 40, barrier));
            var second = pool.submit(() -> mergeRange(31, 70, barrier));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(history.findByUserId(userId)).hasSize(50);
        deleted.add(69L);
        RecentRestaurantListResponse first = service.list(null);
        assertThat(first.content()).extracting(Card::restaurantId)
                .containsExactly(70L, 68L, 67L, 66L, 65L, 64L, 63L, 62L, 61L, 60L);
        assertThat(first.content().getFirst().saveCount()).isEqualTo(17);
        List<Long> all = new ArrayList<>();
        RecentRestaurantListResponse page = first;
        while (true) {
            page.content().forEach(card -> all.add(card.restaurantId()));
            if (!page.hasNext()) break;
            page = service.list(page.nextCursor());
        }
        assertThat(all).hasSize(49).doesNotHaveDuplicates().endsWith(21L);
        given(currentUser.currentUserId()).willReturn(userId + 100_000);
        assertThat(service.list(null).content()).isEmpty();
    }

    @Test
    void equalTimestampsKeepTheSameTopFiftyAfterRetry() {
        var first = new RecentRestaurantRequest(LongStream.rangeClosed(1, 50)
                .mapToObj(id -> new Visit(id, NOW)).toList());
        service.merge(first);
        service.merge(new RecentRestaurantRequest(List.of(new Visit(51L, NOW))));
        service.merge(first);
        assertThat(history.findByUserId(userId)).extracting(RecentRestaurant::getRestaurantId)
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(2, 51).boxed().toList());
    }

    private Void mergeRange(long first, long last, CyclicBarrier barrier) throws Exception {
        barrier.await(15, TimeUnit.SECONDS);
        service.merge(new RecentRestaurantRequest(LongStream.rangeClosed(first, last)
                .mapToObj(id -> new Visit(id, NOW.minusSeconds(100 - id))).toList()));
        return null;
    }
}
