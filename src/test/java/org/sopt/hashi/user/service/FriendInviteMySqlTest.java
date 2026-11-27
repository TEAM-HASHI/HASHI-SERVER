package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.point.domain.PointTransactionRepository;
import org.sopt.hashi.point.domain.PointTransactionType;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FriendInviteMySqlTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired OnboardingService onboarding;
    @Autowired FriendInviteService invites;
    @Autowired UserRepository users;
    @Autowired PointPort points;
    @Autowired PointTransactionRepository ledger;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 동시_가입에서도_각_친구와_초대자에게_한번씩_지급하고_미회수액을_상계한다() throws Exception {
        long inviter = existing(1);
        authenticate(new MemberPrincipal(inviter), AuthRoles.USER);
        var invitation = invites.getMine();
        assertThat(invites.getMine()).isEqualTo(invitation);
        assertThat(invitation.inviteLink()).endsWith("?inviteCode=" + invitation.inviteCode());
        assertThat(invites.isValid(invitation.inviteCode())).isTrue();
        points.earnReviewReward(inviter, 901L);
        points.use(inviter, 500L, "예약", PointSourceType.RESERVATION, 902L);
        points.reclaimReviewReward(inviter, 901L);
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(signup(11, invitation.inviteCode(), start));
            var second = pool.submit(signup(12, invitation.inviteCode(), start));
            for (long friend : List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))) {
                assertThat(users.findById(friend).orElseThrow().getInvitedByUserId()).isEqualTo(inviter);
                assertThat(points.getBalance(friend)).isEqualTo(4000);
                assertThat(ledger.findByTypeAndSourceTypeAndSourceId(PointTransactionType.EARN,
                        PointSourceType.INVITE_SENT, friend)).isPresent();
                assertThat(ledger.findByTypeAndSourceTypeAndSourceId(PointTransactionType.EARN,
                        PointSourceType.INVITE_RECEIVED, friend)).isPresent();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(points.getBalance(inviter)).isEqualTo(7500);
        authenticate(new OnboardingPrincipal(11L), AuthRoles.ONBOARDING);
        assertThatThrownBy(() -> onboarding.completeOnboarding(request(11, invitation.inviteCode())))
                .isInstanceOf(BusinessException.class);
        assertThat(points.getBalance(inviter)).isEqualTo(7500);
    }

    @Test
    void 입력값은_가입시에_다시_검증하고_코드없는_가입과_실패한_가입에는_보상을_주지_않는다() {
        long inviter = existing(2);
        authenticate(new MemberPrincipal(inviter), AuthRoles.USER);
        String code = invites.getMine().inviteCode();
        assertThat(invites.isValid(code)).isTrue();
        transactions.executeWithoutResult(tx -> users.findByIdForUpdate(inviter).orElseThrow().withdraw());
        assertThat(invites.isValid(code)).isFalse();
        authenticate(new OnboardingPrincipal(21L), AuthRoles.ONBOARDING);
        assertThatThrownBy(() -> onboarding.completeOnboarding(request(21, code)))
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_INVITE_CODE);
        assertThat(users.existsByNickname("friend21")).isFalse();
        long fresh = onboarding.completeOnboarding(request(21, null)).userId();
        assertThat(points.getBalance(fresh)).isZero();
        assertThat(users.findById(fresh).orElseThrow().getInvitedByUserId()).isNull();
        authenticate(new MemberPrincipal(fresh), AuthRoles.USER);
        String own = invites.getMine().inviteCode();
        authenticate(new OnboardingPrincipal(21L), AuthRoles.ONBOARDING);
        // 동일 소셜 주체가 다른 프로필로 자기 코드를 다시 제출해도 계정 연결과 보상이 함께 롤백된다.
        assertThatThrownBy(() -> onboarding.completeOnboarding(request(22, own)))
                .isInstanceOf(BusinessException.class);
        assertThat(users.existsByNickname("friend22")).isFalse();
        assertThat(points.getBalance(fresh)).isZero();
    }

    @Test
    void 가입이_롤백되면_양쪽_보상도_사라지고_재시도에서_한번만_지급된다() {
        long inviter = existing(3);
        authenticate(new MemberPrincipal(inviter), AuthRoles.USER);
        String code = invites.getMine().inviteCode().toLowerCase(java.util.Locale.ROOT);
        authenticate(new OnboardingPrincipal(31L), AuthRoles.ONBOARDING);
        transactions.executeWithoutResult(tx -> {
            onboarding.completeOnboarding(request(31, code));
            tx.setRollbackOnly();
        });
        assertThat(users.existsByNickname("friend31")).isFalse();
        assertThat(points.getBalance(inviter)).isZero();
        long friend = onboarding.completeOnboarding(request(31, code)).userId();
        assertThat(points.getBalance(friend)).isEqualTo(4000);
        assertThat(points.getBalance(inviter)).isEqualTo(4000);
    }

    private Callable<Long> signup(long id, String code, CyclicBarrier start) {
        return () -> {
            authenticate(new OnboardingPrincipal(id), AuthRoles.ONBOARDING);
            try {
                start.await(15, TimeUnit.SECONDS);
                return onboarding.completeOnboarding(request(id, code)).userId();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private long existing(long id) {
        var request = request(id, null);
        return users.saveAndFlush(User.onboard(request.nickname(), null, request.birthDate(), request.phone(),
                request.email(), null)).getId();
    }

    private CompleteOnboardingRequest request(long id, String code) {
        return new CompleteOnboardingRequest("friend" + id, "FRIEND", LocalDate.of(2000, 1, 1),
                "010%08d".formatted(id), "friend" + id + "@example.test", null, null, code);
    }

    private void authenticate(Object principal, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role))));
    }
}
