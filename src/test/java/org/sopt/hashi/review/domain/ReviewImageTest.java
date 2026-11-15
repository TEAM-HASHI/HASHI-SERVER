package org.sopt.hashi.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewImageTest {
    @ParameterizedTest
    @ValueSource(ints = {-1, 10, Integer.MAX_VALUE})
    void 생성과_순서변경은_동일한_범위로_검증한다(int order) {
        assertThatThrownBy(() -> ReviewImage.create("legacy/review.jpg", order))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewImage.createAsset(UUID.randomUUID(), order))
                .isInstanceOf(IllegalArgumentException.class);
        var image = ReviewImage.createAsset(UUID.randomUUID(), 0);
        assertThatThrownBy(() -> image.setDisplayOrder(order)).isInstanceOf(IllegalArgumentException.class);
        assertThat(image.getDisplayOrder()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    void 생성과_순서변경은_양쪽_경계값을_허용한다(int order) {
        assertThat(ReviewImage.create("legacy/review.jpg", order).getDisplayOrder()).isEqualTo(order);
        var image = ReviewImage.createAsset(UUID.randomUUID(), order);
        assertThat(image.getDisplayOrder()).isEqualTo(order);
        image.stageOrderChange();
        image.setDisplayOrder(order);
        assertThat(image.getDisplayOrder()).isEqualTo(order);
    }
}
