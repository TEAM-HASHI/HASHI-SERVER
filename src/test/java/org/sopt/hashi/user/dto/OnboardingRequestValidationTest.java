package org.sopt.hashi.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OnboardingRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void 프로필_legacy_key와_asset_ID를_함께_받지_않는다() {
        CompleteOnboardingRequest request = request("profiles/legacy.jpg", UUID.randomUUID());

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("profileImageSourceValid");
    }

    @Test
    void 프로필_이미지가_없거나_asset_ID만_있으면_유효하다() {
        assertThat(validator.validate(request(null, null))).isEmpty();
        assertThat(validator.validate(request(null, UUID.randomUUID()))).isEmpty();
    }

    @Test
    void 공백_legacy_key는_이미지_없음으로_저장하지_않고_거부한다() {
        assertThat(validator.validate(request(" ", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("profileImageSourceValid");
    }

    private CompleteOnboardingRequest request(String key, UUID assetId) {
        return new CompleteOnboardingRequest(
                "하시", "HASHI", LocalDate.of(1998, 1, 1),
                "01012345678", "hashi@example.com", key, assetId);
    }
}
