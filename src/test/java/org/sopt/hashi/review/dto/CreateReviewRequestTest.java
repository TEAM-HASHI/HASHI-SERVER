package org.sopt.hashi.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateReviewRequestTest {
    @Test
    void 새_asset_목록과_기존_key_목록은_각각_허용하고_혼합은_거절한다() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request(null, List.of(UUID.randomUUID())))).isEmpty();
            assertThat(validator.validate(request(List.of("uploads/reviews/old.jpg"), null))).isEmpty();
            assertThat(validator.validate(request(null, null))).isEmpty();
            assertThat(validator.validate(request(List.of(), List.of())))
                    .extracting(violation -> violation.getPropertyPath().toString()).contains("imageSourceValid");
            assertThat(validator.validate(request(null, java.util.Arrays.asList((UUID) null))))
                    .extracting(violation -> violation.getMessage())
                    .containsExactly("이미지 asset ID는 비어 있을 수 없습니다");
        }
    }

    private CreateReviewRequest request(List<String> keys, List<UUID> ids) {
        return new CreateReviewRequest(1L, 5, List.of("GOOD_VALUE"), "충분히 긴 리뷰 내용입니다.", keys, ids);
    }
}
