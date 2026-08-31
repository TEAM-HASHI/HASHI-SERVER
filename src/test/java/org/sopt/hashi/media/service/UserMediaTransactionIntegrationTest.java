package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.service.OnboardingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserMediaTransactionIntegrationTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";
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

    @MockitoBean
    private AuthAccountPort authAccountPort;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    @Test
    void 온보딩_완료는_USER저장과_PROFILE소유권_인계_BIND를_함께_commit한다() {
        ImageAsset asset = imageAssetRepository.saveAndFlush(profileAsset(true));
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ONBOARDING, 99L));

        var response = onboardingService.completeOnboarding(request(
                "profile-success@hashi.test", "01010000001", asset.getPublicId()));

        assertThat(userRepository.findById(response.userId())).hasValueSatisfying(user -> {
            assertThat(user.getProfileImageKey()).isNull();
            assertThat(user.getProfileImageAssetId()).isEqualTo(asset.getPublicId());
        });
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()))
                .hasValueSatisfying(reloaded -> {
                    assertThat(reloaded.getCreatorActorType())
                            .isEqualTo(MediaOwnerType.ONBOARDING);
                    assertThat(reloaded.isOwnedBy(MediaOwnerType.USER, response.userId())).isTrue();
                    assertThat(reloaded.getBindingStatus()).isEqualTo(ImageBindingStatus.BOUND);
                });
        verify(authAccountPort).linkOnboardingAccount(response.userId());
    }

    @Test
    void auth_계정_연결이_실패하면_USER와_PROFILE_asset은_모두_원상복구된다() {
        ImageAsset asset = imageAssetRepository.saveAndFlush(profileAsset(true));
        doThrow(new IllegalStateException("auth link failed"))
                .when(authAccountPort).linkOnboardingAccount(org.mockito.ArgumentMatchers.anyLong());

        assertThatThrownBy(() -> onboardingService.completeOnboarding(request(
                "profile-auth-rollback@hashi.test", "01010000002", asset.getPublicId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auth link failed");

        assertThat(userRepository.existsByEmail("profile-auth-rollback@hashi.test")).isFalse();
        assertUnboundOnboardingOwner(asset.getPublicId());
    }

    @Test
    void PROFILE_claim이_실패하면_앞서_저장한_USER도_rollback된다() {
        ImageAsset asset = imageAssetRepository.saveAndFlush(profileAsset(false));
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ONBOARDING, 99L));

        assertThatThrownBy(() -> onboardingService.completeOnboarding(request(
                "profile-media-rollback@hashi.test", "01010000003", asset.getPublicId())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.INVALID_STATE));

        assertThat(userRepository.existsByEmail("profile-media-rollback@hashi.test")).isFalse();
        assertUnboundOnboardingOwner(asset.getPublicId());
    }

    private void assertUnboundOnboardingOwner(UUID assetId) {
        assertThat(imageAssetRepository.findByPublicId(assetId))
                .hasValueSatisfying(reloaded -> {
                    assertThat(reloaded.isOwnedBy(MediaOwnerType.ONBOARDING, 99L)).isTrue();
                    assertThat(reloaded.getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
                });
    }

    private ImageAsset profileAsset(boolean ready) {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.PROFILE,
                MediaOwnerType.ONBOARDING,
                99L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5));
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        if (ready) {
            asset.completeCurrentProcessing(
                    jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032,
                    SOURCE_CHECKSUM);
        }
        return asset;
    }

    private CompleteOnboardingRequest request(String email, String phone, UUID assetId) {
        return new CompleteOnboardingRequest(
                "하시" + phone.substring(phone.length() - 1),
                "HASHI",
                LocalDate.of(1998, 1, 1),
                phone,
                email,
                null,
                assetId);
    }
}
