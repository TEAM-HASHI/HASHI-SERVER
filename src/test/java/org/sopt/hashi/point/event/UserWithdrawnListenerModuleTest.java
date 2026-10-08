package org.sopt.hashi.point.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.point.domain.PointTransactionRepository;
import org.sopt.hashi.point.domain.PointTransactionType;
import org.sopt.hashi.point.service.PointService;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * point 모듈만 부트스트랩해 탈퇴 이벤트 발행 → Event Publication Registry 기록 → 리스너 실행까지 실제 경로를 검증한다
 * (testing.md §2·§3). 같은 이벤트를 두 번 발행해도 잔액 소멸과 FORFEIT 원장은 한 번만 반영돼야 한다(멱등).
 * 미완료 publication이 0이 되는 것으로 리스너 완료를 확인한다(completion-mode: delete).
 */
@Testcontainers(disabledWithoutDocker = true)
@ApplicationModuleTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserWithdrawnListenerModuleTest {

    private static final Long USER_ID = 7L;

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_point")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private PointService pointService;

    @Autowired
    private PointTransactionRepository pointTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 공유 모듈 auth의 토큰 저장소가 요구한다 — 이 테스트는 Redis를 쓰지 않는다
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM event_publication");
        jdbcTemplate.update("DELETE FROM point_transaction");
        jdbcTemplate.update("DELETE FROM point_account");
    }

    @Test
    void 탈퇴_이벤트를_받아_잔액을_소멸하고_같은_이벤트를_다시_받아도_한_번만_반영한다(Scenario scenario) {
        pointService.earn(USER_ID, 500L, "리뷰 작성 보상", PointSourceType.REVIEW, 100L);

        scenario.publish(new UserWithdrawnEvent(USER_ID))
                .andWaitForStateChange(() -> pointService.getBalance(USER_ID), balance -> balance == 0L)
                .andVerify(balance -> assertThat(balance).isZero());
        scenario.publish(new UserWithdrawnEvent(USER_ID))
                .andWaitForStateChange(this::incompletePublicationCount, count -> count == 0)
                .andVerify(count -> assertThat(count).isZero());

        assertThat(pointService.getBalance(USER_ID)).isZero();
        assertThat(pointTransactionRepository.findAll())
                .filteredOn(tx -> tx.getType() == PointTransactionType.FORFEIT)
                .singleElement()
                .satisfies(tx -> {
                    assertThat(tx.getAmount()).isEqualTo(500L);
                    assertThat(tx.getSourceType()).isEqualTo(PointSourceType.USER);
                    assertThat(tx.getSourceId()).isEqualTo(USER_ID);
                });
    }

    private Integer incompletePublicationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_publication", Integer.class);
    }
}
