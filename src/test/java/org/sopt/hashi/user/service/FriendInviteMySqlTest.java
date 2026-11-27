package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.sopt.hashi.user.internal.phone.OctomoClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        "hashi.signup-phone.enabled=true", "hashi.signup-phone.api-key=local-fixture",
        "hashi.signup-phone.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FriendInviteMySqlTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired OnboardingService onboarding;
    @Autowired SignupPhoneService phone;
    @Autowired org.sopt.hashi.user.domain.SignupPhoneStore phoneStore;
    @Autowired SignupPhonePolicy policy;
    @Autowired UserProfileService profiles;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean OctomoClient octomo;

    @BeforeEach
    void allowSimulatedMessage() {
        when(octomo.exists(anyString(), anyString())).thenReturn(true);
    }
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
        prove(request(21, null).phone());
        long fresh = onboarding.completeOnboarding(request(21, null)).userId();
        assertThat(points.getBalance(fresh)).isZero();
        assertThat(users.findById(fresh).orElseThrow().getInvitedByUserId()).isNull();
        authenticate(new MemberPrincipal(fresh), AuthRoles.USER);
        String own = invites.getMine().inviteCode();
        authenticate(new OnboardingPrincipal(21L), AuthRoles.ONBOARDING);
        prove(request(22, own).phone());
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
        prove(request(31, code).phone());
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
                prove(request(id, code).phone());
                start.await(15, TimeUnit.SECONDS);
                return onboarding.completeOnboarding(request(id, code)).userId();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void prove(String number) {
        var issued = phone.issue(number);
        assertThat(phone.confirm(number, issued.challengeId())).isTrue();
    }

    @Test
    void 보상을_받은_번호로_탈퇴_후_재가입하면_인증은_필수이고_양쪽_보상은_건너뛴다() {
        long inviter = existing(40);
        authenticate(new MemberPrincipal(inviter), AuthRoles.USER);
        String code = invites.getMine().inviteCode();
        var first = request(41, code);
        authenticate(new OnboardingPrincipal(41L), AuthRoles.ONBOARDING);
        prove(first.phone());
        long firstId = onboarding.completeOnboarding(first).userId();
        authenticate(new MemberPrincipal(firstId), AuthRoles.USER);
        profiles.updateMyInfo(new org.sopt.hashi.user.dto.UpdateMyInfoRequest(null, null, null,
                request(43, null).phone(), null, null));
        transactions.executeWithoutResult(tx -> users.findByIdForUpdate(firstId).orElseThrow().withdraw());

        authenticate(new OnboardingPrincipal(42L), AuthRoles.ONBOARDING);
        var next = request(42, code);
        var samePhone = new CompleteOnboardingRequest(next.nickname(), next.nameEng(), next.birthDate(), first.phone(),
                next.email(), null, null, code);
        assertThatThrownBy(() -> onboarding.completeOnboarding(samePhone))
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.PHONE_PROOF_REQUIRED);
        prove(first.phone());
        long nextId = onboarding.completeOnboarding(samePhone).userId();
        assertThat(points.getBalance(nextId)).isZero();
        assertThat(points.getBalance(inviter)).isEqualTo(4000);
    }

    @Test
    void 인증은_세션과_번호에_묶이고_재발급으로_이전_도전을_대체한다() {
        authenticate(new OnboardingPrincipal(51L), AuthRoles.ONBOARDING);
        String number = request(51, null).phone();
        var issued = phone.issue(number);
        var replacement = phone.issue(number);
        assertThatThrownBy(() -> phone.confirm(number, issued.challengeId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> phone.confirm(request(52, null).phone(), replacement.challengeId()))
                .isInstanceOf(BusinessException.class);
        authenticate(new OnboardingPrincipal(51L), AuthRoles.ONBOARDING);
        assertThatThrownBy(() -> phone.confirm(number, replacement.challengeId())).isInstanceOf(BusinessException.class);
        var last = phone.issue(number);
        when(octomo.exists(anyString(), anyString())).thenReturn(false);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(phone.confirm(number, last.challengeId())).isFalse();
        }
        assertThatThrownBy(() -> phone.confirm(number, last.challengeId()))
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.PHONE_PROOF_LIMIT);
        assertThatThrownBy(() -> phone.issue(number))
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.PHONE_PROOF_LIMIT);
        assertThatThrownBy(() -> onboarding.completeOnboarding(request(51, null)))
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.PHONE_PROOF_REQUIRED);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void 발급_횟수는_연락처를_정정하거나_토큰을_갱신해도_같은_계정에_합산한다() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM signup_phone_rate_limit", Integer.class);
        authenticate(new OnboardingPrincipal(53L), AuthRoles.ONBOARDING);
        phone.issue(request(53, null).phone());
        authenticate(new OnboardingPrincipal(53L), AuthRoles.ONBOARDING);
        phone.issue(request(54, null).phone());
        byte[] actorBucket = policy.digest("actor-limit", "53");
        assertThat(jdbc.queryForObject("SELECT hits FROM signup_phone_rate_limit WHERE bucket=?", Integer.class, actorBucket))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signup_phone_rate_limit", Integer.class)).isEqualTo(before + 1);
    }

    @Test
    void 외부_조회가_성공해도_인증_유효기간이_끝나면_가입에_쓸_수_없다() {
        authenticate(new OnboardingPrincipal(61L), AuthRoles.ONBOARDING);
        var signup = request(61, null);
        var issued = phone.issue(signup.phone());
        assertThat(phone.confirm(signup.phone(), issued.challengeId())).isTrue();
        jdbc.update("UPDATE signup_phone_challenge SET expires_at=UTC_TIMESTAMP(6) WHERE challenge_id=?",
                issued.challengeId().toString());
        assertThatThrownBy(() -> onboarding.completeOnboarding(signup))
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.PHONE_PROOF_REQUIRED);
    }

    @Test
    void 동일_번호를_서로_다른_트랜잭션에서_동시에_선점해도_한_건만_통과한다() throws Exception {
        byte[] digest = policy.digest("reward-phone-v1", request(71, null).phone());
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> claim = () -> {
            start.await(10, TimeUnit.SECONDS);
            return transactions.execute(tx -> phoneStore.claimReward(digest));
        };
        try {
            var one = pool.submit(claim);
            var two = pool.submit(claim);
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
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
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
        authentication.setDetails(java.util.UUID.randomUUID().toString());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
