package org.sopt.hashi.user.collection.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.springframework.stereotype.Component;

/**
 * 저장 식당 enrich(architecture.md §5-2) — 컬렉션은 restaurant_id만 갖고 있으므로 카드 정보는 {@link RestaurantPort}의
 * 카드 조회로, 대표 이미지 파생본은 {@link MediaPort}로 bulk 조회한다. 삭제된 식당은 포트가 돌려주지 않으므로
 * 결과에 없는 식당이 곧 "표시 불가" 식당이다.
 */
@Component
class SavedRestaurantEnricher {

    private final RestaurantPort restaurantPort;
    private final MediaPort mediaPort;

    SavedRestaurantEnricher(RestaurantPort restaurantPort, MediaPort mediaPort) {
        this.restaurantPort = restaurantPort;
        this.mediaPort = mediaPort;
    }

    /** 표시 가능한(삭제되지 않은) 식당의 카드 정보를 id로 찾을 수 있게 돌려준다. */
    Map<Long, RestaurantCardInfo> findActiveCards(Collection<Long> restaurantIds) {
        if (restaurantIds.isEmpty()) {
            return Map.of();
        }
        return restaurantPort.findActiveCards(restaurantIds).stream()
                .collect(Collectors.toMap(RestaurantCardInfo::id, Function.identity()));
    }

    /**
     * 대표 이미지 파생본을 한 번에 조회해 둔다 — 카드·커버마다 단건 조회하지 않는다(coding-style §4-2).
     * 호출 측이 실제로 보여줄 식당(페이지 한 장, 커버 후보)만 넘겨 필요한 만큼만 조회한다.
     */
    ThumbnailProjection loadThumbnails(Collection<RestaurantCardInfo> cards) {
        List<MediaImageRequest> requests = cards.stream()
                .map(RestaurantCardInfo::thumbnailImageReference)
                .filter(Objects::nonNull)
                .map(ImageReference::assetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_THUMBNAIL))
                .distinct()
                .toList();
        if (requests.isEmpty()) {
            return new ThumbnailProjection(Map.of());
        }
        return new ThumbnailProjection(mediaPort.findImages(requests));
    }

    /** 조회해 둔 파생본에서 식당별 대표 이미지 URL·응답 이미지를 고른다. legacy key 이미지는 URL만 있다. */
    record ThumbnailProjection(Map<MediaImageRequest, MediaImage> images) {

        ThumbnailProjection {
            images = Map.copyOf(images);
        }

        MediaImageSelection select(RestaurantCardInfo card) {
            ImageReference reference = card.thumbnailImageReference();
            MediaImage image = reference == null || reference.assetId() == null
                    ? null
                    : images.get(new MediaImageRequest(reference.assetId(), MediaImageRole.RESTAURANT_THUMBNAIL));
            return MediaImageSelection.from(reference, image);
        }
    }
}
