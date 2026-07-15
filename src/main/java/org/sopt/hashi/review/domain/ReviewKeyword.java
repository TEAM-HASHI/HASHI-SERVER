package org.sopt.hashi.review.domain;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;

/** 리뷰 작성 화면에 노출하는 키워드 코드와 표시 문구의 단일 기준. */
@Getter
public enum ReviewKeyword {

    FOOD_IS_DELICIOUS("음식이 맛있어요"),
    MILD_SEASONING("향신료가 강하지 않아요"),
    STAFF_IS_KIND("직원분이 친절해요"),
    TRADITIONAL_ATMOSPHERE("전통적이에요"),
    SPACIOUS_INTERIOR("매장이 넓어요"),
    CLEAN_INTERIOR("매장이 청결해요"),
    FAST_SERVICE("음식이 빨리 나와요"),
    PHOTO_FRIENDLY("사진이 잘 나와요"),
    GOOD_VALUE("가성비가 좋아요"),
    GOOD_FOR_CONVERSATION("대화하기 좋아요");

    private final String label;

    ReviewKeyword(String label) {
        this.label = label;
    }

    public static Optional<ReviewKeyword> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(keyword -> keyword.name().equals(code))
                .findFirst();
    }

    /** 기존 데이터가 표시 문구로 저장되어 있어도 그대로 응답할 수 있도록 호환한다. */
    public static String labelOfStoredValue(String storedValue) {
        return fromCode(storedValue)
                .map(ReviewKeyword::getLabel)
                .orElse(storedValue);
    }
}
