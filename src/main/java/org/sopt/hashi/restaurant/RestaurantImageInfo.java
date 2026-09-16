package org.sopt.hashi.restaurant;

import org.sopt.hashi.media.MediaImage;

/** 식당 Aggregate가 소유하는 stable 이미지 association과 media projection. */
public record RestaurantImageInfo(
        Long restaurantImageId,
        int displayOrder,
        MediaImage image
) {
}
