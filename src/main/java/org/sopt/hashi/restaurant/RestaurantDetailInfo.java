package org.sopt.hashi.restaurant;

import org.sopt.hashi.media.ImageReference;

/**
 * 모듈 간 전달용 식당 상세 DTO. 예약 상세 등 요약(id·name)보다 많은 표시 정보가 필요한 조회에 쓴다.
 * 대표 이미지는 object key가 아닌 전환기 {@link ImageReference}로 전달한다. 대표 이미지가 없으면 null.
 */
public record RestaurantDetailInfo(
        Long id,
        String name,
        String nameJa,
        String address,
        ImageReference thumbnailImageReference
) {
}
