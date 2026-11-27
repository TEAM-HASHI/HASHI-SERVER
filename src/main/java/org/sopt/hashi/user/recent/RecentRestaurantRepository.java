package org.sopt.hashi.user.recent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RecentRestaurantRepository extends JpaRepository<RecentRestaurant, Long> {
    List<RecentRestaurant> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
