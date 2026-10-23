package org.sopt.hashi.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UpdateReviewRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void 수정_가능한_필드는_모두_필수다() {
        UpdateReviewRequest request = new UpdateReviewRequest(null, null, null, null, null);

        Set<ConstraintViolation<UpdateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("rating", "keywordCodes", "content", "imageSourceValid");
    }

    @Test
    void 빈_이미지_목록은_전체_삭제_의미로_허용한다() {
        UpdateReviewRequest request = validRequest(List.of());

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 별점_키워드_내용_이미지_개수_범위를_검증한다() {
        UpdateReviewRequest request = new UpdateReviewRequest(
                6,
                List.of(),
                "짧은 내용",
                IntStream.range(0, 11)
                        .mapToObj(index -> "uploads/reviews/%d.jpg".formatted(index))
                        .toList(), null
        );

        Set<ConstraintViolation<UpdateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("rating", "keywordCodes", "content", "imageFileKeys");
    }

    @Test
    void 컬렉션_항목의_공백과_길이를_검증한다() {
        UpdateReviewRequest request = new UpdateReviewRequest(
                5,
                List.of(" "),
                "직원분들이 친절하고 음식이 정말 맛있었습니다.",
                List.of("x".repeat(501)), null
        );

        Set<ConstraintViolation<UpdateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("keywordCodes[0].<list element>",
                        "imageFileKeys[0].<list element>");
    }

    private UpdateReviewRequest validRequest(List<String> imageFileKeys) {
        return new UpdateReviewRequest(
                5,
                List.of("FOOD_IS_DELICIOUS"),
                "직원분들이 친절하고 음식이 정말 맛있었습니다.",
                imageFileKeys, null
        );
    }

    @Test
    void 새_이미지_항목과_ID_검증은_한글_메시지를_반환한다() {
        assertThat(validator.validate(new UpdateReviewRequest(5, List.of("GOOD_VALUE"),
                "충분히 긴 리뷰 내용입니다.", null, java.util.Arrays.asList((UpdateReviewRequest.Image) null))))
                .extracting(ConstraintViolation::getMessage).containsExactly("이미지 항목은 비어 있을 수 없습니다");
        assertThat(validator.validate(new UpdateReviewRequest(5, List.of("GOOD_VALUE"),
                "충분히 긴 리뷰 내용입니다.", null, List.of(new UpdateReviewRequest.Image(0L, null)))))
                .extracting(ConstraintViolation::getMessage).containsExactly("리뷰 이미지 ID는 양수여야 합니다");
    }

    @Test
    void 새_이미지_wrapper는_정확히_하나의_source와_상호배타_목록을_요구한다() {
        var id = java.util.UUID.randomUUID();
        assertThat(validator.validate(new UpdateReviewRequest(5, List.of("GOOD_VALUE"),
                "충분히 긴 리뷰 내용입니다.", null, List.of(new UpdateReviewRequest.Image(null, id))))).isEmpty();
        for (var image : List.of(new UpdateReviewRequest.Image(null, null),
                new UpdateReviewRequest.Image(1L, id), new UpdateReviewRequest.Image(0L, null))) {
            assertThat(validator.validate(new UpdateReviewRequest(5, List.of("GOOD_VALUE"),
                    "충분히 긴 리뷰 내용입니다.", null, List.of(image)))).isNotEmpty();
        }
        assertThat(validator.validate(new UpdateReviewRequest(5, List.of("GOOD_VALUE"),
                "충분히 긴 리뷰 내용입니다.", List.of(), List.of()))).isNotEmpty();
    }
}
