package org.sopt.hashi.restaurant.domain;

public record RestaurantCursor(
        RestaurantSort sort,
        Double rating,
        Long popularityScore,
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
            case BASIC -> validateBasicCursor(rating, popularityScore);
            case POPULAR -> validatePopularCursor(rating, popularityScore);
            case RATING -> validateRatingCursor(rating, popularityScore);
        }
    }

    private static void validateBasicCursor(Double rating, Long popularityScore) {
        if (rating != null || popularityScore != null) {
            throw new IllegalArgumentException("Basic cursor must not contain sort value");
        }
    }

    private static void validatePopularCursor(Double rating, Long popularityScore) {
        if (rating != null || popularityScore == null) {
            throw new IllegalArgumentException("Popular cursor must contain only popularity score");
        }
    }

    private static void validateRatingCursor(Double rating, Long popularityScore) {
        if (rating == null || popularityScore != null) {
            throw new IllegalArgumentException("Rating cursor must contain only rating");
        }
    }
}
