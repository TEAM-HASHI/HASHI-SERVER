package org.sopt.hashi.restaurant;

/**
 * 모듈 간 전달용 식당 요약 DTO. 의존 모듈의 목록 표시·enrich에 필요한 최소 필드만 담는다(§2-2 {@code <Context>Info}).
 * {@code imageUrl}은 대표 이미지의 presigned GET URL이다(coding-style §4-2). 대표 이미지가 없으면 null.
 */
public record RestaurantInfo(Long id, String name, String imageUrl) {
}
