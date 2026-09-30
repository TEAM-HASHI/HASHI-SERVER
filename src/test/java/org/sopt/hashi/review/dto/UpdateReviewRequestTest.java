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
        UpdateReviewRequest request = new UpdateReviewRequest(null, null, null, null);

        Set<ConstraintViolation<UpdateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("rating", "keywordCodes", "content", "imageFileKeys");
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
                        .toList()
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
                List.of("x".repeat(501))
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
                imageFileKeys
        );
    }
}
