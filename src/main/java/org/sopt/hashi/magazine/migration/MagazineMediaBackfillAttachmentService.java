package org.sopt.hashi.magazine.migration;

import java.time.Duration;
import java.util.List;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillClaim;
import org.sopt.hashi.media.MediaBackfillPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MagazineMediaBackfillAttachmentService {

    private final MagazineRepository magazineRepository;
    private final MediaBackfillPort mediaBackfillPort;
    private final MagazineMediaBackfillCheckpointStore checkpointStore;

    MagazineMediaBackfillAttachmentService(
            MagazineRepository magazineRepository,
            MediaBackfillPort mediaBackfillPort,
            MagazineMediaBackfillCheckpointStore checkpointStore
    ) {
        this.magazineRepository = magazineRepository;
        this.mediaBackfillPort = mediaBackfillPort;
        this.checkpointStore = checkpointStore;
    }

    /** 슬롯 변경, media claim과 fenced cursor 전진이 같은 쓰기 transaction에 참여한다. */
    @Transactional
    public MagazineMediaBackfillOutcome attachAndRecord(
            MagazineMediaBackfillCandidate candidate,
            MediaBackfillAssetInfo asset,
            Lease lease,
            Duration leaseDuration
    ) {
        boolean valid = lease.mode() == MagazineMediaBackfillMode.ATTACH
                && candidate.target() == lease.target()
                && asset.state() == MediaBackfillAssetInfo.State.READY
                && asset.purpose() == candidate.target().mediaTarget().purpose();
        if (!valid) {
            throw new IllegalArgumentException("invalid magazine media backfill attachment");
        }
        Magazine magazine = magazineRepository.findByIdForUpdate(candidate.magazineId()).orElse(null);
        boolean attached = magazine != null && switch (candidate.target()) {
            case MAGAZINE_BANNER -> magazine.attachBackfilledBanner(candidate.legacyKey(), asset.assetId());
            case MAGAZINE_THUMBNAIL -> magazine.attachBackfilledThumbnail(candidate.legacyKey(), asset.assetId());
        };
        MagazineMediaBackfillOutcome outcome = attached
                ? MagazineMediaBackfillOutcome.ATTACHED : MagazineMediaBackfillOutcome.SKIPPED;
        if (attached) {
            mediaBackfillPort.claimReady(List.of(
                    new MediaBackfillClaim(asset.assetId(), asset.purpose(), asset.identityHash())));
        }
        checkpointStore.recordProgress(lease, candidate.magazineId(), outcome, leaseDuration);
        return outcome;
    }
}
