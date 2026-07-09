package org.sopt.hashi.restaurant.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

    @Query("""
            select distinct r.name
            from Restaurant r
            where r.active = true
              and lower(r.name) like lower(concat('%', :keyword, '%'))
            order by r.name asc
            """)
    List<String> findRestaurantSuggestionKeywords(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select distinct m.name
            from Restaurant r
            join r.menus m
            where r.active = true
              and lower(m.name) like lower(concat('%', :keyword, '%'))
            order by m.name asc
            """)
    List<String> findMenuSuggestionKeywords(@Param("keyword") String keyword, Pageable pageable);
}
