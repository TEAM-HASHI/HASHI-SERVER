package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.OnboardingPrincipal;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.dto.OnboardingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** auth/media Port를 모킹하지 않고 세 모듈의 실제 MySQL transaction을 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OnboardingMediaAtomicityIntegrationTest {

    private static final Long ONBOARDING_SUBJECT_ID = 99L;
    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM auth_account");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new OnboardingPrincipal(ONBOARDING_SUBJECT_ID), null,
                        List.of(new SimpleGrantedAuthority(AuthRoles.ONBOARDING))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 회원_인증계정_이미지_소유권_인계는_실제_세_모듈에서_함께_commit된다() {
        ImageAsset asset = profileAsset(true);

        OnboardingResponse response = onboardingService.completeOnboarding(request(asset));

        assertThat(userRepository.findById(response.userId())).hasValueSatisfying(user ->
                assertThat(user.getProfileImageAssetId()).isEqualTo(asset.getPublicId()));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT user_id FROM auth_account
                WHERE provider = 'KAKAO' AND provider_user_id = ?
                """, Long.class, ONBOARDING_SUBJECT_ID.toString())).isEqualTo(response.userId());
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()))
                .hasValueSatisfying(reloaded -> {
                    assertThat(reloaded.isOwnedBy(MediaOwnerType.USER, response.userId())).isTrue();
                    assertThat(reloaded.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
                    assertThat(reloaded.getCreatorActorType()).isEqualTo(MediaOwnerType.ONBOARDING);
                    assertThat(reloaded.getCreatorSubjectId()).isEqualTo(ONBOARDING_SUBJECT_ID);
                });
    }

    @Test
    void media_claim_실패는_이미_INSERT된_회원과_인증계정까지_rollback한다() {
        ImageAsset asset = profileAsset(false);

        assertThatThrownBy(() -> onboardingService.completeOnboarding(request(asset)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.INVALID_STATE));

        assertAllRolledBack(asset);
    }

    @Test
    void 소유권_인계까지_flush된_뒤_실패해도_회원_인증계정_asset이_함께_rollback한다() {
        ImageAsset asset = profileAsset(true);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            OnboardingResponse response = onboardingService.completeOnboarding(request(asset));
            imageAssetRepository.flush();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM auth_account WHERE user_id = ?", Integer.class,
                    response.userId())).isEqualTo(1);
            assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow()
                    .isOwnedBy(MediaOwnerType.USER, response.userId())).isTrue();
            throw new IllegalStateException("onboarding transaction failed");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("onboarding transaction failed");

        assertAllRolledBack(asset);
    }

    @Test
    void 다른_회원에게_연결된_asset도_없는_asset과_같은_오류로_존재를_숨긴다() {
        ImageAsset asset = profileAsset(true);
        User existingUser = userRepository.saveAndFlush(User.onboard(
                "기존회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000002", "existing@hashi.test", null, asset.getPublicId()));
        transactionTemplate.executeWithoutResult(status -> {
            ImageAsset loaded = imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow();
            loaded.handoffOwnerAndBind(
                    MediaOwnerType.ONBOARDING, ONBOARDING_SUBJECT_ID,
                    MediaOwnerType.USER, existingUser.getId());
        });

        assertThatThrownBy(() -> onboardingService.completeOnboarding(request(asset)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.ASSET_NOT_FOUND));

        CompleteOnboardingRequest missingAssetRequest = new CompleteOnboardingRequest(
                "원자성회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000001", "atomicity@hashi.test", null, UUID.randomUUID());
        assertThatThrownBy(() -> onboardingService.completeOnboarding(missingAssetRequest))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.ASSET_NOT_FOUND));

        assertThat(userRepository.count()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_account", Integer.class))
                .isZero();
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow()
                .isOwnedBy(MediaOwnerType.USER, existingUser.getId())).isTrue();
    }

    private void assertAllRolledBack(ImageAsset asset) {
        assertThat(userRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_account", Integer.class))
                .isZero();
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()))
                .hasValueSatisfying(reloaded -> {
                    assertThat(reloaded.isOwnedBy(MediaOwnerType.ONBOARDING, ONBOARDING_SUBJECT_ID))
                            .isTrue();
                    assertThat(reloaded.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
                });
    }

    private ImageAsset profileAsset(boolean ready) {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId, MediaPurpose.PROFILE, MediaOwnerType.ONBOARDING, ONBOARDING_SUBJECT_ID,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg", 1024L, LocalDateTime.now().plusMinutes(5));
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        if (ready) {
            asset.completeCurrentProcessing(
                    jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        }
        return imageAssetRepository.saveAndFlush(asset);
    }

    private CompleteOnboardingRequest request(ImageAsset asset) {
        return new CompleteOnboardingRequest(
                "원자성회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000001", "atomicity@hashi.test", null, asset.getPublicId());
    }
}
