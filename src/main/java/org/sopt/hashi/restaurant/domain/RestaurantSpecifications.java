package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.SetJoin;
import org.springframework.data.jpa.domain.Specification;

public final class RestaurantSpecifications {

    private RestaurantSpecifications() {
    }

    public static Specification<Restaurant> notDeleted() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.isFalse(root.get("deleted"));
    }

    public static Specification<Restaurant> cursorAfter(RestaurantCursor cursor) {
        return (root, query, criteriaBuilder) -> {
            if (cursor == null) {
                return criteriaBuilder.conjunction();
            }
            return switch (cursor.sort()) {
                case BASIC -> criteriaBuilder.lessThan(root.get("id"), cursor.id());
                case POPULAR -> criteriaBuilder.or(
                        criteriaBuilder.lessThan(root.get("reviewCount"), cursor.reviewCount()),
                        criteriaBuilder.and(
                                criteriaBuilder.equal(root.get("reviewCount"), cursor.reviewCount()),
                                criteriaBuilder.lessThan(root.get("rating"), cursor.rating())
                        ),
                        criteriaBuilder.and(
                                criteriaBuilder.equal(root.get("reviewCount"), cursor.reviewCount()),
                                criteriaBuilder.equal(root.get("rating"), cursor.rating()),
                                criteriaBuilder.lessThan(root.get("id"), cursor.id())
                        )
                );
                case RATING -> criteriaBuilder.or(
                        criteriaBuilder.lessThan(root.get("rating"), cursor.rating()),
                        criteriaBuilder.and(
                                criteriaBuilder.equal(root.get("rating"), cursor.rating()),
                                criteriaBuilder.lessThan(root.get("id"), cursor.id())
                        )
                );
            };
        };
    }

    public static Specification<Restaurant> genreEquals(RestaurantGenre genre) {
        return (root, query, criteriaBuilder) -> {
            if (genre == null) {
                return criteriaBuilder.conjunction();
            }
            return criteriaBuilder.equal(root.get("genre"), genre);
        };
    }

    public static Specification<Restaurant> foodCategoryEquals(RestaurantFoodCategory foodCategory) {
        return (root, query, criteriaBuilder) -> {
            if (foodCategory == null) {
                return criteriaBuilder.conjunction();
            }
            return criteriaBuilder.equal(root.get("foodCategory"), foodCategory);
        };
    }

    public static Specification<Restaurant> curationTypeEquals(RestaurantCurationType curationType) {
        return (root, query, criteriaBuilder) -> {
            if (curationType == null) {
                return criteriaBuilder.conjunction();
            }
            if (query != null) {
                query.distinct(true);
            }
            SetJoin<Restaurant, RestaurantCurationType> curationTypes = root.joinSet("curationTypes");
            return criteriaBuilder.equal(curationTypes, curationType);
        };
    }

    public static Specification<Restaurant> keywordContains(String keyword) {
        return (root, query, criteriaBuilder) -> {
            if (keyword == null || keyword.isBlank()) {
                return criteriaBuilder.conjunction();
            }
            if (query != null) {
                query.distinct(true);
            }

            String likeKeyword = "%" + keyword.trim().toLowerCase() + "%";
            Join<Restaurant, RestaurantMenu> menus = root.join("menus", JoinType.LEFT);

            return criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), likeKeyword),
                    criteriaBuilder.like(criteriaBuilder.lower(menus.get("name")), likeKeyword)
            );
        };
    }
}
