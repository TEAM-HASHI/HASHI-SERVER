package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** EXISTS를 사용해 메뉴·태그 개수에 따른 중복 행과 점수 부풀림을 방지한다. */
public class RestaurantSearchRepositoryImpl implements RestaurantSearchRepository {

    private final EntityManager entityManager;

    public RestaurantSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<RestaurantSearchPageItem> search(RestaurantSearchQuery input, RestaurantGenre genre,
            RestaurantCurationType curationType, RestaurantSort sort, List<BigDecimal> after, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Restaurant> root = query.from(Restaurant.class);
        Expression<Integer> matched = cb.literal(0);
        Expression<Integer> names = cb.literal(0);
        Expression<Integer> menus = cb.literal(0);
        Expression<Integer> tags = cb.literal(0);
        for (String term : input.terms()) {
            String pattern = RestaurantSearchKeyword.containsPattern(term);
            Predicate name = input.hashtagOnly() ? cb.disjunction()
                    : cb.like(cb.lower(root.get("name")), pattern, RestaurantSearchKeyword.LIKE_ESCAPE);
            Predicate menu = input.hashtagOnly() ? cb.disjunction() : menuMatches(query, root, cb, pattern);
            Predicate tag = tagMatches(query, root, cb, pattern);
            matched = cb.sum(matched, score(cb, cb.or(name, menu, tag)));
            names = cb.sum(names, score(cb, name));
            menus = cb.sum(menus, score(cb, menu));
            tags = cb.sum(tags, score(cb, tag));
        }
        List<Expression<? extends Number>> order = switch (sort) {
            case BASIC -> List.of(matched, names, menus, tags, root.get("id"));
            case POPULAR -> List.of(root.get("reviewCount"), root.get("rating"), root.get("id"));
            case RATING -> List.of(root.get("rating"), root.get("reviewCount"), root.get("id"));
        };
        List<Predicate> conditions = new ArrayList<>();
        conditions.add(cb.isFalse(root.get("deleted")));
        conditions.add(cb.gt(matched, 0));
        if (genre != null) {
            conditions.add(cb.equal(root.get("genre"), genre));
        }
        if (curationType != null) {
            conditions.add(cb.isMember(curationType, root.get("curationTypes")));
        }
        if (after != null) {
            List<Predicate> alternatives = new ArrayList<>();
            for (int i = 0; i < order.size(); i++) {
                List<Predicate> prefix = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    prefix.add(cb.equal(order.get(j), after.get(j)));
                }
                prefix.add(cb.lt(order.get(i), after.get(i)));
                alternatives.add(cb.and(prefix.toArray(Predicate[]::new)));
            }
            conditions.add(cb.or(alternatives.toArray(Predicate[]::new)));
        }
        var selections = new ArrayList<jakarta.persistence.criteria.Selection<?>>();
        selections.add(root);
        selections.addAll(order);
        query.multiselect(selections).where(conditions.toArray(Predicate[]::new))
                .orderBy(order.stream().map(cb::desc).toList());
        return entityManager.createQuery(query).setMaxResults(limit).getResultList().stream().map(row -> {
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 1; i <= order.size(); i++) {
                values.add(new BigDecimal(row.get(i).toString()));
            }
            return new RestaurantSearchPageItem(row.get(0, Restaurant.class), values);
        }).toList();
    }

    private Expression<Integer> score(CriteriaBuilder cb, Predicate predicate) {
        return cb.<Integer>selectCase().when(predicate, 1).otherwise(0);
    }

    private Predicate menuMatches(CriteriaQuery<?> query, Root<Restaurant> root, CriteriaBuilder cb, String pattern) {
        var sub = query.subquery(Integer.class);
        var menu = sub.from(RestaurantMenu.class);
        sub.select(cb.literal(1)).where(cb.equal(menu.get("restaurant"), root),
                cb.like(cb.lower(menu.get("name")), pattern, RestaurantSearchKeyword.LIKE_ESCAPE));
        return cb.exists(sub);
    }

    private Predicate tagMatches(CriteriaQuery<?> query, Root<Restaurant> root, CriteriaBuilder cb, String pattern) {
        var sub = query.subquery(Integer.class);
        var restaurant = sub.correlate(root);
        var tag = restaurant.<Restaurant, String>joinSet("hashtags");
        sub.select(cb.literal(1)).where(cb.like(cb.lower(tag), pattern, RestaurantSearchKeyword.LIKE_ESCAPE));
        return cb.exists(sub);
    }
}
