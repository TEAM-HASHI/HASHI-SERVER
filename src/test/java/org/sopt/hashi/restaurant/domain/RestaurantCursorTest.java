package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RestaurantCursorTest {

    @Test
    void 기본순_커서는_id만_가진다() {
        RestaurantCursor cursor = new RestaurantCursor(RestaurantSort.BASIC, null, null, 1L);

        assertThat(cursor.sort()).isEqualTo(RestaurantSort.BASIC);
        assertThat(cursor.id()).isEqualTo(1L);
        assertThat(cursor.rating()).isNull();
        assertThat(cursor.popularityScore()).isNull();
    }

    @Test
    void 인기순_커서는_인기점수와_id를_가진다() {
        RestaurantCursor cursor = new RestaurantCursor(RestaurantSort.POPULAR, null, 100L, 1L);

        assertThat(cursor.sort()).isEqualTo(RestaurantSort.POPULAR);
        assertThat(cursor.popularityScore()).isEqualTo(100L);
        assertThat(cursor.id()).isEqualTo(1L);
    }

    @Test
    void 별점순_커서는_별점과_id를_가진다() {
        RestaurantCursor cursor = new RestaurantCursor(RestaurantSort.RATING, 4.8, null, 1L);

        assertThat(cursor.sort()).isEqualTo(RestaurantSort.RATING);
        assertThat(cursor.rating()).isEqualTo(4.8);
        assertThat(cursor.id()).isEqualTo(1L);
    }

    @Test
    void 정렬_기준에_맞지_않는_커서값이면_예외가_발생한다() {
        assertThatThrownBy(() -> new RestaurantCursor(RestaurantSort.BASIC, 4.8, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestaurantCursor(RestaurantSort.POPULAR, null, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestaurantCursor(RestaurantSort.RATING, null, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 커서_id가_양수가_아니면_예외가_발생한다() {
        assertThatThrownBy(() -> new RestaurantCursor(RestaurantSort.BASIC, null, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestaurantCursor(RestaurantSort.BASIC, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
