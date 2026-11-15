package org.sopt.hashi.user.migration;

import java.time.Duration;
import java.util.List;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillClaim;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserProfileBackfillAttachmentService {

    private final UserRepository userRepository;
    private final MediaBackfillPort mediaBackfillPort;
    private final UserProfileBackfillCheckpointStore checkpointStore;

    UserProfileBackfillAttachmentService(
            UserRepository userRepository,
            MediaBackfillPort mediaBackfillPort,
            UserProfileBackfillCheckpointStore checkpointStore
    ) {
        this.userRepository = userRepository;
        this.mediaBackfillPort = mediaBackfillPort;
        this.checkpointStore = checkpointStore;
    }

    /** User 변경, media claim과 fenced cursor 전진은 모두 같은 쓰기 transaction에 참여한다. */
    @Transactional
    public UserProfileBackfillOutcome attachAndRecord(
            UserProfileBackfillCandidate candidate,
            MediaBackfillAssetInfo asset,
            Lease lease,
            Duration leaseDuration
    ) {
        boolean valid = lease.mode() == UserProfileBackfillMode.ATTACH
                && asset.state() == MediaBackfillAssetInfo.State.READY
                && asset.purpose() == MediaAssetPurpose.PROFILE;
        if (!valid) {
            throw new IllegalArgumentException("invalid user profile backfill attachment");
        }
        User user = userRepository.findByIdForUpdate(candidate.userId()).orElse(null);
        boolean attached = user != null
                && user.attachBackfilledProfileImage(candidate.legacyKey(), asset.assetId());
        UserProfileBackfillOutcome outcome = attached
                ? UserProfileBackfillOutcome.ATTACHED : UserProfileBackfillOutcome.SKIPPED;
        if (attached) {
            mediaBackfillPort.claimReady(List.of(
                    new MediaBackfillClaim(asset.assetId(), asset.purpose(), asset.identityHash())));
        }
        checkpointStore.recordProgress(lease, candidate.userId(), outcome, leaseDuration);
        return outcome;
    }
}
