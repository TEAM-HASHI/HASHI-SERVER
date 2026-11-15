package org.sopt.hashi.point.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.point.domain.PointAccountRepository;
import org.sopt.hashi.point.domain.PointTransactionRepository;
import org.sopt.hashi.point.domain.PointTransactionType;
import org.sopt.hashi.point.event.UserWithdrawnListener;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 이벤트 리스너의 멱등성(testing.md §3) — 같은 이벤트를 두 번 받아도 잔액은 한 번만 소멸하고 FORFEIT 원장은 하나여야 한다.
 * 리스너를 실제 저장소와 함께 직접 호출한다(REQUIRES_NEW 트랜잭션이 커밋되도록 테스트는 트랜잭션 밖에서 돈다).
 */
@DataJpaTest
@Import({PointService.class, UserWithdrawnListener.class, JpaAuditingConfig.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:point-forfeit-integration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PointForfeitIntegrationTest {

    @Autowired
    private UserWithdrawnListener listener;

    @Autowired
    private PointService pointService;

    @Autowired
    private PointAccountRepository pointAccountRepository;

    @Autowired
    private PointTransactionRepository pointTransactionRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @AfterEach
    void tearDown() {
        pointTransactionRepository.deleteAllInBatch();
        pointAccountRepository.deleteAllInBatch();
    }

    @Test
    void 탈퇴_이벤트를_두_번_받아도_잔액은_한_번만_소멸하고_FORFEIT_원장은_하나다() {
        pointService.earn(7L, 500L, "리뷰 작성 보상", PointSourceType.REVIEW, 100L);

        listener.on(new UserWithdrawnEvent(7L));
        listener.on(new UserWithdrawnEvent(7L));

        assertThat(pointService.getBalance(7L)).isZero();
        assertThat(pointTransactionRepository.findAll())
                .filteredOn(tx -> tx.getType() == PointTransactionType.FORFEIT)
                .singleElement()
                .satisfies(tx -> {
                    assertThat(tx.getAmount()).isEqualTo(500L);
                    assertThat(tx.getSourceType()).isEqualTo(PointSourceType.USER);
                    assertThat(tx.getSourceId()).isEqualTo(7L);
                });
    }

    @Test
    void 계정이_없거나_잔액이_0이면_원장_없이_끝낸다() {
        pointService.earn(9L, 100L, "리뷰 작성 보상", PointSourceType.REVIEW, 101L);
        pointService.use(9L, 100L, "예약 결제 수수료", PointSourceType.RESERVATION, 102L);

        listener.on(new UserWithdrawnEvent(8L));
        listener.on(new UserWithdrawnEvent(9L));

        assertThat(pointAccountRepository.findByUserId(8L)).isEmpty();
        assertThat(pointService.getBalance(9L)).isZero();
        assertThat(pointTransactionRepository.findAll())
                .noneMatch(tx -> tx.getType() == PointTransactionType.FORFEIT);
    }
}
