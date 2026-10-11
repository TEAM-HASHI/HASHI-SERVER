package org.sopt.hashi.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.dev.DevTokenResponse;
import org.sopt.hashi.auth.dev.DevTokenRole;
import org.sopt.hashi.auth.dev.DevTokenService;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.reservation.dev.DevReservationDataGenerator;
import org.sopt.hashi.restaurant.dev.DevRestaurantDataGenerator;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.service.RestaurantLocationService;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.sopt.hashi.review.dev.DevReviewDataGenerator;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.dev.DevUserDataGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RestaurantService.class, RestaurantLocationService.class, TimeConfig.class,
        DevLocationJobMySqlTest.Fixtures.class})
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevLocationJobMySqlTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dev_location_jobs").withUsername("hashi").withPassword("hashi");

    @Autowired DevDataService scenarios;
    @Autowired RestaurantRepository restaurants;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RestaurantLocationJobRepository jobs;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;
    @MockitoBean DevUserDataGenerator users;
    @MockitoBean DevReservationDataGenerator reservations;
    @MockitoBean DevReviewDataGenerator reviews;
    @MockitoBean DevTokenService tokens;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM restaurant_location_job");
        // 위치 저장 밖의 개발용 회원/예약/리뷰/토큰 생성만 합성 응답으로 대체한다.
        given(users.createUsers(10)).willReturn(List.of(901L));
        given(reviews.createReviews(anyLong(), anyList())).willReturn(List.of(801L));
        given(tokens.issue(DevTokenRole.USER, 901L))
                .willReturn(new DevTokenResponse("synthetic-token", "USER", 901L));
    }

    @Test
    void 두_개발용_시나리오의_외부_transaction에서도_위치_작업이_함께_저장된다() throws Exception {
        CyclicBarrier selected = new CyclicBarrier(2);
        var realRepository = mockingDetails(jobs).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = realRepository.answer(invocation);
            selected.await(10, TimeUnit.SECONDS);
            return result;
        }).when(jobs).findActiveForUpdate(anyLong());
        long before = restaurants.count();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(scenarios::createScenario);
            var second = executor.submit(scenarios::createScenario);
            assertThat(first.get(15, TimeUnit.SECONDS).restaurantId()).isNotNull();
            assertThat(second.get(15, TimeUnit.SECONDS).restaurantId()).isNotNull();
        }
        assertThat(restaurants.count()).isEqualTo(before + 2);
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void 뒤의_시나리오_생성이_실패하면_식당과_위치_작업도_함께_rollback한다() {
        long before = restaurants.count();
        given(users.createUsers(10)).willThrow(new IllegalStateException("synthetic scenario failure"));
        assertThatThrownBy(scenarios::createScenario).isInstanceOf(IllegalStateException.class);
        assertThat(restaurants.count()).isEqualTo(before);
        assertThat(jobs.count()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        // local/dev 프로필 전체를 활성화하지 않고 실제 두 transaction proxy만 등록한다.
        @Bean
        DevRestaurantDataGenerator restaurantGenerator(RestaurantService restaurants) {
            return new DevRestaurantDataGenerator(restaurants);
        }

        @Bean
        DevDataService scenarioService(DevRestaurantDataGenerator restaurants, DevUserDataGenerator users,
                                      DevReservationDataGenerator reservations, DevReviewDataGenerator reviews,
                                      DevTokenService tokens) {
            return new DevDataService(restaurants, users, reservations, reviews, tokens);
        }
    }
}
