package org.sopt.hashi.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReviewKeywordTest {

    @Test
    void 최종_키워드_code와_문구를_화면_순서대로_제공한다() {
        assertThat(Arrays.stream(ReviewKeyword.values()).map(Enum::name))
                .containsExactly(
                        "FOOD_IS_DELICIOUS",
                        "MILD_SEASONING",
                        "GOOD_FOR_SOLO_DINING",
                        "STAFF_IS_KIND",
                        "SPACIOUS_INTERIOR",
                        "CLEAN_INTERIOR",
                        "FAST_SERVICE",
                        "PHOTO_FRIENDLY",
                        "GOOD_VALUE",
                        "GOOD_FOR_CONVERSATION");
        assertThat(Arrays.stream(ReviewKeyword.values()).map(ReviewKeyword::getLabel))
                .containsExactly(
                        "음식이 맛있어요",
                        "향신료가 강하지 않아요",
                        "혼밥하기 좋아요",
                        "친절해요",
                        "매장이 넓어요",
                        "매장이 청결해요",
                        "음식이 빨리 나와요",
                        "사진이 잘 나와요",
                        "가성비가 좋아요",
                        "대화하기 좋아요");
        assertThat(ReviewKeyword.fromCode("TRADITIONAL_ATMOSPHERE")).isEmpty();
    }

    @Test
    void 저장된_code와_레거시_label을_같은_키워드로_복원한다() {
        assertThat(ReviewKeyword.fromStoredValue("FOOD_IS_DELICIOUS"))
                .contains(ReviewKeyword.FOOD_IS_DELICIOUS);
        assertThat(ReviewKeyword.fromStoredValue("음식이 맛있어요"))
                .contains(ReviewKeyword.FOOD_IS_DELICIOUS);
    }

    @Test
    void 알_수_없는_저장값은_키워드_code로_변환하지_않는다() {
        assertThat(ReviewKeyword.fromStoredValue("과거 미확인 문구")).isEmpty();
        assertThat(ReviewKeyword.labelOfStoredValue("과거 미확인 문구"))
                .isEqualTo("과거 미확인 문구");
    }
}
