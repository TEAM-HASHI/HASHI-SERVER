package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;

public record RestaurantCursor(
        RestaurantSort sort,
        BigDecimal rating,
        Long reviewCount,
        Long id
) {

    public RestaurantCursor {
        if (sort == null) {
            throw new IllegalArgumentException("Cursor sort must not be null");
        }
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Cursor id must be positive");
        }
        switch (sort) {
            case BASIC -> validateBasicCursor(rating, reviewCount);
            case POPULAR -> validatePopularCursor(rating, reviewCount);
            case RATING -> validateRatingCursor(rating, reviewCount);
        }
    }

    private static void validateBasicCursor(BigDecimal rating, Long reviewCount) {
        if (rating != null || reviewCount != null) {
            throw new IllegalArgumentException("Basic cursor must not contain sort value");
        }
    }

    private static void validatePopularCursor(BigDecimal rating, Long reviewCount) {
        if (rating == null || reviewCount == null) {
            throw new IllegalArgumentException("Popular cursor must contain review count and rating");
        }
    }

    private static void validateRatingCursor(BigDecimal rating, Long reviewCount) {
        if (rating == null || reviewCount != null) {
            throw new IllegalArgumentException("Rating cursor must contain only rating");
        }
    }
}
