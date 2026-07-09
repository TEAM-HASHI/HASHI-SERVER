package org.sopt.hashi.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReviewRatingTest {

    @Test
    void 유효한_평점이면_값을_생성한다() {
        ReviewRating rating = ReviewRating.from(5);

        assertThat(rating.value()).isEqualTo(5);
    }

    @Test
    void 평점이_최솟값보다_작으면_예외가_발생한다() {
        assertThatThrownBy(() -> ReviewRating.from(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 평점이_최댓값보다_크면_예외가_발생한다() {
        assertThatThrownBy(() -> ReviewRating.from(6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
