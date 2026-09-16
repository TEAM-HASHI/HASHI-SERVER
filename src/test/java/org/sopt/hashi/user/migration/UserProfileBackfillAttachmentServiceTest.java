package org.sopt.hashi.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;

class UserProfileBackfillAttachmentServiceTest {

    private static final String IDENTITY = "b".repeat(64);
    private static final Duration DURATION = Duration.ofMinutes(5);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MediaBackfillPort mediaBackfillPort = mock(MediaBackfillPort.class);
    private final UserProfileBackfillCheckpointStore checkpointStore =
            mock(UserProfileBackfillCheckpointStore.class);
    private final UserProfileBackfillAttachmentService service = new UserProfileBackfillAttachmentService(
            userRepository, mediaBackfillPort, checkpointStore);

    @Test
    void 잠근_프로필이_그대로면_claim과_cursor를_같이_기록한다() {
        User user = user("profiles/legacy.jpg");
        UserProfileBackfillCandidate candidate = new UserProfileBackfillCandidate(11L, "profiles/legacy.jpg");
        MediaBackfillAssetInfo asset = readyAsset();
        Lease lease = lease();
        given(userRepository.findByIdForUpdate(11L)).willReturn(Optional.of(user));

        assertThat(service.attachAndRecord(candidate, asset, lease, DURATION))
                .isEqualTo(UserProfileBackfillOutcome.ATTACHED);

        assertThat(user.getProfileImageKey()).isEqualTo("profiles/legacy.jpg");
        assertThat(user.getProfileImageAssetId()).isEqualTo(asset.assetId());
        verify(mediaBackfillPort).claimReady(argThat(claims -> claims.size() == 1
                && claims.iterator().next().assetId().equals(asset.assetId())
                && claims.iterator().next().identityHash().equals(IDENTITY)
                && claims.iterator().next().purpose() == MediaAssetPurpose.PROFILE));
        verify(checkpointStore).recordProgress(lease, 11L, UserProfileBackfillOutcome.ATTACHED, DURATION);
    }

    @Test
    void 잠금_조회에서_탈퇴나_삭제된_회원이_제외되면_claim하지_않는다() {
        Lease lease = lease();
        given(userRepository.findByIdForUpdate(11L)).willReturn(Optional.empty());

        assertThat(service.attachAndRecord(
                new UserProfileBackfillCandidate(11L, "profiles/legacy.jpg"), readyAsset(), lease, DURATION))
                .isEqualTo(UserProfileBackfillOutcome.SKIPPED);

        verify(mediaBackfillPort, never()).claimReady(any());
        verify(checkpointStore).recordProgress(lease, 11L, UserProfileBackfillOutcome.SKIPPED, DURATION);
    }

    @Test
    void 잠금_대기_중_source가_변경되면_현재_프로필을_보존한다() {
        User user = user("profiles/changed.jpg");
        given(userRepository.findByIdForUpdate(11L)).willReturn(Optional.of(user));

        assertThat(service.attachAndRecord(new UserProfileBackfillCandidate(11L, "profiles/old.jpg"),
                readyAsset(), lease(), DURATION)).isEqualTo(UserProfileBackfillOutcome.SKIPPED);

        assertThat(user.getProfileImageKey()).isEqualTo("profiles/changed.jpg");
        assertThat(user.getProfileImageAssetId()).isNull();
        verify(mediaBackfillPort, never()).claimReady(any());
    }

    @Test
    void PROFILE이_아니거나_READY가_아닌_asset은_DB_접근_전에_거부한다() {
        UserProfileBackfillCandidate candidate = new UserProfileBackfillCandidate(11L, "profiles/legacy.jpg");
        MediaBackfillAssetInfo wrongPurpose = new MediaBackfillAssetInfo(
                UUID.randomUUID(), MediaAssetPurpose.RESTAURANT, IDENTITY, MediaBackfillAssetInfo.State.READY);
        MediaBackfillAssetInfo processing = new MediaBackfillAssetInfo(
                UUID.randomUUID(), MediaAssetPurpose.PROFILE, IDENTITY, MediaBackfillAssetInfo.State.PROCESSING);

        assertThatThrownBy(() -> service.attachAndRecord(candidate, wrongPurpose, lease(), DURATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.attachAndRecord(candidate, processing, lease(), DURATION))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepository, mediaBackfillPort, checkpointStore);
    }

    private MediaBackfillAssetInfo readyAsset() {
        return new MediaBackfillAssetInfo(UUID.randomUUID(), MediaAssetPurpose.PROFILE,
                IDENTITY, MediaBackfillAssetInfo.State.READY);
    }

    private Lease lease() {
        return new Lease(UUID.randomUUID(), UUID.randomUUID(), UserProfileBackfillMode.ATTACH, 20L);
    }

    private User user(String key) {
        return User.onboard("프로필회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000001", "profile@hashi.test", key);
    }
}
