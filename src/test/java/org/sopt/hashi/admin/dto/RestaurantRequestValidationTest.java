package org.sopt.hashi.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RestaurantRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void 식당_등록에는_이미지와_해시태그가_각각_최소_한_개_필요하다() {
        CreateRestaurantRequest request = new CreateRestaurantRequest(
                "하시 스시",
                "Hashi Sushi",
                "한 줄 소개",
                "상세 설명",
                "도쿄도 시부야구",
                "도쿄",
                "sushi",
                "sushi",
                "JPY",
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000),
                null,
                List.of(),
                null,
                List.of(),
                createBusinessHours()
        );

        Set<ConstraintViolation<CreateRestaurantRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("imageKeys", "hashtags");
    }

    @Test
    void 식당_수정에서_이미지나_해시태그를_보내면_빈_목록일_수_없다() {
        UpdateRestaurantRequest request = new UpdateRestaurantRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        Set<ConstraintViolation<UpdateRestaurantRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("imageKeys", "hashtags");
    }

    private static List<CreateRestaurantRequest.BusinessHourRequest> createBusinessHours() {
        return Arrays.stream(DayOfWeek.values())
                .map(day -> new CreateRestaurantRequest.BusinessHourRequest(
                        day,
                        null,
                        null,
                        null,
                        null,
                        true
                ))
                .toList();
    }
}
