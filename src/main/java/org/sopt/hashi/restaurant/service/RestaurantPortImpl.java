package org.sopt.hashi.restaurant.service;

import java.util.Optional;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.springframework.stereotype.Component;

/**
 * ⚠️ 스텁 — restaurant 도메인 본체 개발 전, 부팅 시 빈 배선만을 위한 임시 구현.
 * 항상 "존재하지 않음"(false·empty)을 반환하므로 통합 시나리오에서 이 반환값을 신뢰하면 안 된다.
 * 도메인 본체(엔티티·Repository) 구현 시 실제 조회로 교체한다.
 */
@Component
class RestaurantPortImpl implements RestaurantPort {

    @Override
    public boolean existsById(Long restaurantId) {
        return false;
    }

    @Override
    public Optional<RestaurantInfo> findSummaryById(Long restaurantId) {
        return Optional.empty();
    }
}
