package org.sopt.hashi.restaurant;

/**
 * 모듈 간 전달용 식당 상세 DTO. 예약 상세 등 요약(id·name)보다 많은 표시 정보가 필요한 조회에 쓴다.
 * {@code imageUrl}은 대표 이미지의 조회 URL이다(restaurant가 object key를 조회 URL로 변환해 노출,
 * coding-style §4-2). 대표 이미지가 없으면 null.
 */
public record RestaurantDetailInfo(
        Long id,
        String name,
        String nameJa,
        String address,
        String imageUrl) {
}
