package org.sopt.hashi.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MagazineRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void 등록은_두_슬롯을_asset_ID만으로_받을_수_있다() {
        assertThat(validator.validate(create(
                null, UUID.randomUUID(), null, UUID.randomUUID()))).isEmpty();
    }

    @Test
    void 등록은_각_슬롯마다_정확히_하나의_source가_필요하다() {
        assertThat(validator.validate(create(
                null, null, "magazines/thumbnail.jpg", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("bannerImageSourceValid");
        assertThat(validator.validate(create(
                "magazines/banner.jpg", UUID.randomUUID(), "magazines/thumbnail.jpg", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("bannerImageSourceValid");
    }

    @Test
    void 공백_key와_asset_ID를_함께_보내도_거부한다() {
        assertThat(validator.validate(create(
                " ", UUID.randomUUID(), null, UUID.randomUUID())))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("bannerImageSourceValid");
    }

    @Test
    void 수정은_생략한_슬롯을_유지하고_동시_source만_거부한다() {
        assertThat(validator.validate(new UpdateMagazineRequest(
                "수정 제목", null, null, null, null, null))).isEmpty();
        assertThat(validator.validate(new UpdateMagazineRequest(
                null, null, null, "magazines/thumbnail.jpg", UUID.randomUUID(), null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("thumbnailImageSourceValid");
    }

    private CreateMagazineRequest create(
            String bannerKey,
            UUID bannerAssetId,
            String thumbnailKey,
            UUID thumbnailAssetId
    ) {
        return new CreateMagazineRequest(
                "이번 주 매거진", bannerKey, bannerAssetId, thumbnailKey, thumbnailAssetId,
                "https://www.instagram.com/p/test/");
    }
}
