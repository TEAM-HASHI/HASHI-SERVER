package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import org.sopt.hashi.media.ImageReference;

/**
 * 모듈 간 전달용 식당 카드 DTO(#216). 저장 컬렉션처럼 타 모듈이 식당 목록을 카드로 보여주며 평점·리뷰 수로 정렬하고
 * 음식점 분류로 거를 때 쓴다. 요약({@link RestaurantInfo})에는 없는 정렬·필터 값을 담되, 상세({@link RestaurantDetailInfo})가
 * 함께 읽는 영업시간·이미지 전체·가격대는 싣지 않아 컬렉션 하나(수백 건)를 통째로 읽어도 가볍다.
 * {@code placeType}은 음식점 분류("restaurant"·"cafe"·"bar", #211)다.
 * 대표 이미지는 object key가 아닌 전환기 {@link ImageReference}로 전달한다. 대표 이미지가 없으면 null.
 */
public record RestaurantCardInfo(
        Long id,
        String name,
        String area,
        String foodCategory,
        String placeType,
        BigDecimal rating,
        long reviewCount,
        ImageReference thumbnailImageReference
) {
}
