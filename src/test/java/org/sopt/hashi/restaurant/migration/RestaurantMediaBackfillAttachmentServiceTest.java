package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Lease;
import org.springframework.test.util.ReflectionTestUtils;

class RestaurantMediaBackfillAttachmentServiceTest {

    private static final String IDENTITY = "b".repeat(64);

    private final RestaurantRepository restaurantRepository = mock(RestaurantRepository.class);
    private final MediaBackfillPort mediaBackfillPort = mock(MediaBackfillPort.class);
    private final RestaurantMediaBackfillCheckpointStore checkpointStore =
            mock(RestaurantMediaBackfillCheckpointStore.class);
    private final RestaurantMediaBackfillAttachmentService service =
            new RestaurantMediaBackfillAttachmentService(
                    restaurantRepository, mediaBackfillPort, checkpointStore);

    @Test
    void 잠근_aggregate가_그대로면_asset_claim과_cursor를_같이_기록한다() {
        Restaurant restaurant = restaurant();
        RestaurantImage image = RestaurantImage.createLegacy("restaurants/legacy.jpg", 4);
        ReflectionTestUtils.setField(image, "id", 11L);
        restaurant.replaceImages(List.of(image));
        ReflectionTestUtils.setField(restaurant, "id", 100L);
        RestaurantMediaBackfillCandidate candidate = new RestaurantMediaBackfillCandidate(
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                11L, 100L, "restaurants/legacy.jpg");
        MediaBackfillAssetInfo asset = readyAsset();
        Lease lease = lease();
        Duration duration = Duration.ofMinutes(5);
        given(restaurantRepository.findByIdForUpdate(100L)).willReturn(Optional.of(restaurant));

        RestaurantMediaBackfillOutcome outcome = service.attachAndRecord(
                candidate, asset, lease, duration);

        assertThat(outcome).isEqualTo(RestaurantMediaBackfillOutcome.ATTACHED);
        assertThat(image.getFileKey()).isEqualTo("restaurants/legacy.jpg");
        assertThat(image.getDisplayOrder()).isEqualTo(4);
        assertThat(image.getImageAssetId()).isEqualTo(asset.assetId());
        verify(mediaBackfillPort).claimReady(argThat(claims -> claims.size() == 1
                && claims.iterator().next().assetId().equals(asset.assetId())
                && claims.iterator().next().identityHash().equals(IDENTITY)));
        verify(checkpointStore).recordProgress(
                lease, 11L, RestaurantMediaBackfillOutcome.ATTACHED, duration);
    }

    @Test
    void 잠금_대기_중_source가_바뀌었으면_claim하지_않고_cursor만_전진한다() {
        Restaurant restaurant = restaurant();
        RestaurantImage image = RestaurantImage.createLegacy("restaurants/changed.jpg", 1);
        ReflectionTestUtils.setField(image, "id", 11L);
        restaurant.replaceImages(List.of(image));
        ReflectionTestUtils.setField(restaurant, "id", 100L);
        RestaurantMediaBackfillCandidate stale = new RestaurantMediaBackfillCandidate(
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                11L, 100L, "restaurants/old.jpg");
        Lease lease = lease();
        Duration duration = Duration.ofMinutes(5);
        given(restaurantRepository.findByIdForUpdate(100L)).willReturn(Optional.of(restaurant));

        RestaurantMediaBackfillOutcome outcome = service.attachAndRecord(
                stale, readyAsset(), lease, duration);

        assertThat(outcome).isEqualTo(RestaurantMediaBackfillOutcome.SKIPPED);
        assertThat(image.getImageAssetId()).isNull();
        verify(mediaBackfillPort, never()).claimReady(any());
        verify(checkpointStore).recordProgress(
                lease, 11L, RestaurantMediaBackfillOutcome.SKIPPED, duration);
    }

    private MediaBackfillAssetInfo readyAsset() {
        return new MediaBackfillAssetInfo(
                UUID.randomUUID(), MediaAssetPurpose.RESTAURANT, IDENTITY,
                MediaBackfillAssetInfo.State.READY);
    }

    private Lease lease() {
        return new Lease(
                UUID.randomUUID(), UUID.randomUUID(),
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.ATTACH, 20L);
    }

    private Restaurant restaurant() {
        return Restaurant.create(
                "backfill 식당", "backfill restaurant", "소개", "상세 설명",
                "도쿄도", "도쿄", RestaurantGenre.SUSHI, "초밥",
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000));
    }
}
