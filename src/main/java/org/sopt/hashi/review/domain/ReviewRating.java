package org.sopt.hashi.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewRating {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    @Column(name = "rating", nullable = false)
    private int value;

    private ReviewRating(int value) {
        validate(value);
        this.value = value;
    }

    public static ReviewRating from(int value) {
        return new ReviewRating(value);
    }

    public int value() {
        return value;
    }

    private static void validate(int value) {
        if (value < MIN_RATING || value > MAX_RATING) {
            throw new IllegalArgumentException("rating must be between 1 and 5.");
        }
    }
}
