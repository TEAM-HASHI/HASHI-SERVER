package org.sopt.hashi.restaurant;

/**
 * 모듈 간 전달용 식당 요약 DTO. 의존 모듈의 표시·enrich에 필요한 최소 필드만 담는다(§2-2 {@code <Context>Info}).
 */
public record RestaurantInfo(Long id, String name) {
}
