package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthAccountPort authAccountPort;

    @Mock
    private MediaPort mediaPort;

    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        onboardingService = new OnboardingService(userRepository, authAccountPort, mediaPort);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            assertThat(user.getProfileImageAssetId()).isNull();
            ReflectionTestUtils.setField(user, "id", 7L);
            return user;
        });
    }

    @Test
    void asset_프로필은_회원과_auth_계정을_연결한_뒤_새_USER에게_claim한다() {
        UUID assetId = UUID.randomUUID();

        var response = onboardingService.completeOnboarding(request(null, assetId));

        assertThat(response.userId()).isEqualTo(7L);
        InOrder order = inOrder(userRepository, authAccountPort, mediaPort);
        order.verify(userRepository).save(any(User.class));
        order.verify(authAccountPort).linkOnboardingAccount(7L);
        order.verify(mediaPort).claimOnboardingProfile(assetId, 7L);
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(user.capture());
        assertThat(user.getValue().getProfileImageAssetId()).isEqualTo(assetId);
    }

    @Test
    void 소유권_claim_실패시_회원의_asset_ID는_아직_연결되지_않는다() {
        UUID assetId = UUID.randomUUID();
        doThrow(new IllegalStateException("claim failed"))
                .when(mediaPort).claimOnboardingProfile(assetId, 7L);

        assertThatThrownBy(() -> onboardingService.completeOnboarding(request(null, assetId)))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(user.capture());
        assertThat(user.getValue().getProfileImageAssetId()).isNull();
    }

    @Test
    void legacy_프로필은_media_claim을_호출하지_않는다() {
        onboardingService.completeOnboarding(request("profiles/legacy.jpg", null));

        verify(authAccountPort).linkOnboardingAccount(7L);
        verify(mediaPort, never()).claimOnboardingProfile(any(), any());
    }

    private CompleteOnboardingRequest request(String key, UUID assetId) {
        return new CompleteOnboardingRequest(
                "하시", "HASHI", LocalDate.of(1998, 1, 1),
                "01012345678", "hashi@example.com", key, assetId);
    }
}
