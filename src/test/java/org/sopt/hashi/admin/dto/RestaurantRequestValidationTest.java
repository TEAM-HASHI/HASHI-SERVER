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
import java.util.UUID;
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
                "restaurant",
                "JPY",
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                createBusinessHours()
        );

        Set<ConstraintViolation<CreateRestaurantRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("imageSourceValid", "hashtags");
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

    @Test
    void 식당_수정에서_현지_식당명을_보내면_공백일_수_없다() {
        UpdateRestaurantRequest request = new UpdateRestaurantRequest(
                null,
                " ",
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
                null,
                null,
                null
        );

        Set<ConstraintViolation<UpdateRestaurantRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("localName");
    }

    @Test
    void 식당_수정의_기존_메뉴_ID는_양수여야_한다() {
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
                null,
                List.of(new UpdateRestaurantRequest.MenuRequest(
                        0L,
                        "시오라멘",
                        "메뉴 설명",
                        null,
                        "JPY",
                        BigDecimal.valueOf(1_000),
                        true
                )),
                null,
                null,
                null
        );

        Set<ConstraintViolation<UpdateRestaurantRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("menus[0].menuId");
    }

    @Test
    void 식당_등록은_legacy_key와_asset_ID를_함께_받지_않는다() {
        CreateRestaurantRequest request = createRequest(
                List.of("restaurants/legacy.jpg"),
                List.of(UUID.randomUUID()),
                List.of()
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("imageSourceValid");
    }

    @Test
    void 식당_등록은_asset_ID만으로_가능하다() {
        CreateRestaurantRequest request = createRequest(
                null,
                List.of(UUID.randomUUID()),
                List.of(new CreateRestaurantRequest.MenuRequest(
                        "메뉴", "설명", null, UUID.randomUUID(),
                        "JPY", BigDecimal.valueOf(1_000), true
                ))
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 메뉴는_key와_asset_ID를_동시에_받지_않는다() {
        CreateRestaurantRequest request = createRequest(
                List.of("restaurants/legacy.jpg"),
                null,
                List.of(new CreateRestaurantRequest.MenuRequest(
                        "메뉴", "설명", "menus/legacy.jpg", UUID.randomUUID(),
                        "JPY", BigDecimal.valueOf(1_000), true
                ))
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("menus[0].imageSourceValid");
    }

    @Test
    void 식당_수정은_legacy_collection과_ordered_wrapper를_함께_받지_않는다() {
        UpdateRestaurantRequest request = updateRequest(
                List.of("restaurants/legacy.jpg"),
                List.of(new UpdateRestaurantRequest.ImageRequest(1L, null))
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("imageCollectionSourceValid");
    }

    @Test
    void 식당_수정_wrapper는_association_ID와_asset_ID중_하나만_받는다() {
        UpdateRestaurantRequest request = updateRequest(
                null,
                List.of(new UpdateRestaurantRequest.ImageRequest(1L, UUID.randomUUID()))
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("images[0].referenceValid");
    }

    private CreateRestaurantRequest createRequest(
            List<String> imageKeys,
            List<UUID> imageAssetIds,
            List<CreateRestaurantRequest.MenuRequest> menus
    ) {
        return new CreateRestaurantRequest(
                "하시 스시", "Hashi Sushi", "한 줄 소개", "상세 설명",
                "도쿄도 시부야구", "도쿄", "sushi", "sushi", "restaurant", "JPY",
                BigDecimal.valueOf(1_000), BigDecimal.valueOf(3_000),
                imageKeys, imageAssetIds, null, menus, List.of("스시"), List.of(),
                createBusinessHours()
        );
    }

    private UpdateRestaurantRequest updateRequest(
            List<String> imageKeys,
            List<UpdateRestaurantRequest.ImageRequest> images
    ) {
        return new UpdateRestaurantRequest(
                null, null, null, null, null, null, null, null, null, null, null,
                imageKeys, images, null, null, null, null
        );
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
