package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import java.util.List;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillClaim;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Lease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RestaurantMediaBackfillAttachmentService {

    private final RestaurantRepository restaurantRepository;
    private final MediaBackfillPort mediaBackfillPort;
    private final RestaurantMediaBackfillCheckpointStore checkpointStore;

    RestaurantMediaBackfillAttachmentService(
            RestaurantRepository restaurantRepository,
            MediaBackfillPort mediaBackfillPort,
            RestaurantMediaBackfillCheckpointStore checkpointStore
    ) {
        this.restaurantRepository = restaurantRepository;
        this.mediaBackfillPort = mediaBackfillPort;
        this.checkpointStore = checkpointStore;
    }

    /** Aggregate 변경, media claim과 fenced cursor 전진을 한 transaction으로 커밋한다. */
    @Transactional
    public RestaurantMediaBackfillOutcome attachAndRecord(
            RestaurantMediaBackfillCandidate candidate,
            MediaBackfillAssetInfo asset,
            Lease lease,
            Duration leaseDuration
    ) {
        validate(candidate, asset, lease);
        Restaurant restaurant = restaurantRepository.findByIdForUpdate(candidate.restaurantId())
                .orElse(null);
        boolean attached = restaurant != null && attach(restaurant, candidate, asset);
        RestaurantMediaBackfillOutcome outcome = attached
                ? RestaurantMediaBackfillOutcome.ATTACHED
                : RestaurantMediaBackfillOutcome.SKIPPED;
        if (attached) {
            mediaBackfillPort.claimReady(List.of(
                    new MediaBackfillClaim(asset.assetId(), asset.purpose(), asset.identityHash())
            ));
        }
        checkpointStore.recordProgress(lease, candidate.associationId(), outcome, leaseDuration);
        return outcome;
    }

    private boolean attach(
            Restaurant restaurant,
            RestaurantMediaBackfillCandidate candidate,
            MediaBackfillAssetInfo asset
    ) {
        return switch (candidate.target()) {
            case RESTAURANT_IMAGE -> restaurant.attachBackfilledImage(
                    candidate.associationId(), candidate.legacyKey(), asset.assetId());
            case RESTAURANT_MENU -> restaurant.attachBackfilledMenuImage(
                    candidate.associationId(), candidate.legacyKey(), asset.assetId());
        };
    }

    private void validate(
            RestaurantMediaBackfillCandidate candidate,
            MediaBackfillAssetInfo asset,
            Lease lease
    ) {
        boolean valid = candidate.target() == lease.target()
                && lease.mode() == RestaurantMediaBackfillMode.ATTACH
                && asset.state() == MediaBackfillAssetInfo.State.READY
                && asset.purpose() == candidate.target().mediaTarget().purpose();
        if (!valid) {
            throw new IllegalArgumentException("invalid restaurant media backfill attachment");
        }
    }
}
