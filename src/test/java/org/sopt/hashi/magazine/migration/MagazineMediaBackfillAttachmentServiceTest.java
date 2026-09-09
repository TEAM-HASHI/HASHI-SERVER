package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillClaim;
import org.sopt.hashi.media.MediaBackfillPort;
import org.springframework.test.util.ReflectionTestUtils;

class MagazineMediaBackfillAttachmentServiceTest {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private final MagazineRepository repository = mock(MagazineRepository.class);
    private final MediaBackfillPort port = mock(MediaBackfillPort.class);
    private final MagazineMediaBackfillCheckpointStore checkpoint = mock(MagazineMediaBackfillCheckpointStore.class);
    private final MagazineMediaBackfillAttachmentService service =
            new MagazineMediaBackfillAttachmentService(repository, port, checkpoint);

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void READY_자산은_슬롯별로_연결하고_claim과_cursor를_함께_기록한다(MagazineMediaBackfillTarget target) {
        Magazine magazine = Magazine.create("매거진", "legacy.jpg", "legacy.jpg", "https://example.test/");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));
        MagazineMediaBackfillCandidate candidate = candidate(target);
        MediaBackfillAssetInfo asset = asset(target.mediaTarget().purpose(), State.READY);
        Lease lease = lease(target);

        assertThat(service.attachAndRecord(candidate, asset, lease, LEASE_DURATION))
                .isEqualTo(MagazineMediaBackfillOutcome.ATTACHED);

        assertThat(target == MagazineMediaBackfillTarget.MAGAZINE_BANNER
                ? magazine.getBannerImageAssetId() : magazine.getThumbnailImageAssetId()).isEqualTo(asset.assetId());
        assertThat(target == MagazineMediaBackfillTarget.MAGAZINE_BANNER
                ? magazine.getThumbnailImageAssetId() : magazine.getBannerImageAssetId()).isNull();
        verify(port).claimReady(List.of(
                new MediaBackfillClaim(asset.assetId(), asset.purpose(), asset.identityHash())));
        verify(checkpoint).recordProgress(lease, 1L, MagazineMediaBackfillOutcome.ATTACHED, LEASE_DURATION);
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 삭제되거나_key가_바뀐_슬롯은_claim하지_않고_건너뛴다(MagazineMediaBackfillTarget target) {
        Lease lease = lease(target);
        MediaBackfillAssetInfo asset = asset(target.mediaTarget().purpose(), State.READY);
        Magazine changed = Magazine.create("매거진", "new.jpg", "new.jpg", "https://example.test/");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(changed));
        assertThat(service.attachAndRecord(candidate(target), asset, lease, LEASE_DURATION))
                .isEqualTo(MagazineMediaBackfillOutcome.SKIPPED);
        Magazine deleted = Magazine.create("매거진", "legacy.jpg", "legacy.jpg", "https://example.test/");
        ReflectionTestUtils.setField(deleted, "deleted", true);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(deleted));
        assertThat(service.attachAndRecord(candidate(target), asset, lease, LEASE_DURATION))
                .isEqualTo(MagazineMediaBackfillOutcome.SKIPPED);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.attachAndRecord(candidate(target), asset, lease, LEASE_DURATION))
                .isEqualTo(MagazineMediaBackfillOutcome.SKIPPED);
        verify(port, never()).claimReady(any());
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 다른_target이나_purpose_또는_PROCESSING은_DB_접근_전에_거부한다(MagazineMediaBackfillTarget target) {
        MagazineMediaBackfillTarget other = target == MagazineMediaBackfillTarget.MAGAZINE_BANNER
                ? MagazineMediaBackfillTarget.MAGAZINE_THUMBNAIL : MagazineMediaBackfillTarget.MAGAZINE_BANNER;
        assertThatThrownBy(() -> service.attachAndRecord(
                candidate(target), asset(target.mediaTarget().purpose(), State.READY), lease(other), LEASE_DURATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.attachAndRecord(
                candidate(target), asset(other.mediaTarget().purpose(), State.READY), lease(target), LEASE_DURATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.attachAndRecord(
                candidate(target), asset(target.mediaTarget().purpose(), State.PROCESSING),
                lease(target), LEASE_DURATION))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, port, checkpoint);
    }

    private MagazineMediaBackfillCandidate candidate(MagazineMediaBackfillTarget target) {
        return new MagazineMediaBackfillCandidate(target, 1L, "legacy.jpg");
    }

    private Lease lease(MagazineMediaBackfillTarget target) {
        return new Lease(UUID.randomUUID(), UUID.randomUUID(), target, MagazineMediaBackfillMode.ATTACH, 1L);
    }

    private MediaBackfillAssetInfo asset(MediaAssetPurpose purpose, State state) {
        return new MediaBackfillAssetInfo(UUID.randomUUID(), purpose, "a".repeat(64), state);
    }
}
