package org.sopt.hashi.restaurant.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

    @Query("""
            select r.name
            from Restaurant r
            where r.active = true
            order by r.popularityScore desc, r.id desc
            """)
    List<String> findRecommendedRestaurantKeywords(Pageable pageable);

    @Query("""
            select m.name
            from RestaurantMenu m
            join m.restaurant r
            where r.active = true
            order by r.popularityScore desc, r.id desc, m.id desc
            """)
    List<String> findRecommendedMenuKeywords(Pageable pageable);

    @Query("""
            select r.name
            from Restaurant r
            where r.active = true
              and (
                    lower(r.name) like lower(concat('%', :keyword, '%'))
                    or lower(coalesce(r.localName, '')) like lower(concat('%', :keyword, '%'))
              )
            order by r.popularityScore desc, r.id desc
            """)
    List<String> findRestaurantSuggestionKeywords(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select m.name
            from RestaurantMenu m
            join m.restaurant r
            where r.active = true
              and lower(m.name) like lower(concat('%', :keyword, '%'))
            order by r.popularityScore desc, r.id desc, m.id desc
            """)
    List<String> findMenuSuggestionKeywords(@Param("keyword") String keyword, Pageable pageable);
}
