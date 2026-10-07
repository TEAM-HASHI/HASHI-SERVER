package org.sopt.hashi.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.List;
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
        assertThat(validator.validate(update(null, null, null, null))).isEmpty();
        assertThat(validator.validate(new UpdateMagazineRequest(
                null, null, null, "magazines/thumbnail.jpg", UUID.randomUUID(), null,
                null, null, null, null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("thumbnailImageSourceValid");
    }

    @Test
    void 등록은_상세_화면_데이터를_모두_채워도_받는다() {
        assertThat(validator.validate(createWithDetail(
                "가".repeat(2000),
                List.of(
                        new MagazineCardNewsRequest("magazines/card-1.jpg", null),
                        new MagazineCardNewsRequest(null, UUID.randomUUID())),
                List.of("이자카야", "가".repeat(20)),
                List.of(1001L, 1002L)))).isEmpty();
    }

    @Test
    void 수정은_목록을_비우는_빈_배열을_받는다() {
        assertThat(validator.validate(update("", List.of(), List.of(), List.of()))).isEmpty();
    }

    @Test
    void 카드뉴스_항목은_key와_asset_중_정확히_하나만_받는다() {
        assertThat(paths(createWithDetail(null,
                List.of(new MagazineCardNewsRequest("magazines/card.jpg", UUID.randomUUID())),
                null, null)))
                .containsExactly("cardNews[0].imageSourceValid");
        assertThat(paths(createWithDetail(null,
                List.of(
                        new MagazineCardNewsRequest("magazines/card.jpg", null),
                        new MagazineCardNewsRequest(null, null)),
                null, null)))
                .containsExactly("cardNews[1].imageSourceValid");
        assertThat(paths(update(null,
                List.of(new MagazineCardNewsRequest(" ", null)), null, null)))
                .containsExactly("cardNews[0].imageSourceValid");
    }

    @Test
    void 카드뉴스_목록의_null_원소와_500자를_넘는_key를_거부한다() {
        assertThat(paths(createWithDetail(null,
                Arrays.asList((MagazineCardNewsRequest) null), null, null)))
                .singleElement()
                .satisfies(path -> assertThat(path).startsWith("cardNews[0]"));
        assertThat(paths(createWithDetail(null,
                List.of(new MagazineCardNewsRequest("k".repeat(501), null)), null, null)))
                .containsExactly("cardNews[0].imageKey");
    }

    @Test
    void 해시태그는_공백과_20자_초과를_거부한다() {
        assertThat(paths(createWithDetail(null, null, List.of("이자카야", " "), null)))
                .singleElement()
                .satisfies(path -> assertThat(path).startsWith("hashtags[1]"));
        assertThat(paths(update(null, null, List.of("가".repeat(21)), null)))
                .singleElement()
                .satisfies(path -> assertThat(path).startsWith("hashtags[0]"));
    }

    @Test
    void 연결_식당_ID는_null과_0_이하와_중복을_거부한다() {
        assertThat(paths(createWithDetail(null, null, null, Arrays.asList(1001L, null))))
                .singleElement()
                .satisfies(path -> assertThat(path).startsWith("restaurantIds[1]"));
        assertThat(paths(update(null, null, null, List.of(0L))))
                .singleElement()
                .satisfies(path -> assertThat(path).startsWith("restaurantIds[0]"));
        assertThat(paths(createWithDetail(null, null, null, List.of(1001L, 1002L, 1001L))))
                .containsExactly("restaurantIdsUnique");
        assertThat(paths(update(null, null, null, List.of(1001L, 1001L))))
                .containsExactly("restaurantIdsUnique");
    }

    @Test
    void 본문은_2000자를_넘으면_거부한다() {
        assertThat(paths(createWithDetail("가".repeat(2001), null, null, null)))
                .containsExactly("content");
        assertThat(paths(update("가".repeat(2001), null, null, null)))
                .containsExactly("content");
    }

    private List<String> paths(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();
    }

    private CreateMagazineRequest create(
            String bannerKey,
            UUID bannerAssetId,
            String thumbnailKey,
            UUID thumbnailAssetId
    ) {
        return new CreateMagazineRequest(
                "이번 주 매거진", bannerKey, bannerAssetId, thumbnailKey, thumbnailAssetId,
                "https://www.instagram.com/p/test/",
                null, null, null, null);
    }

    private CreateMagazineRequest createWithDetail(
            String content,
            List<MagazineCardNewsRequest> cardNews,
            List<String> hashtags,
            List<Long> restaurantIds
    ) {
        return new CreateMagazineRequest(
                "이번 주 매거진", "magazines/banner.jpg", null, "magazines/thumbnail.jpg", null,
                "https://www.instagram.com/p/test/",
                content, cardNews, hashtags, restaurantIds);
    }

    private UpdateMagazineRequest update(
            String content,
            List<MagazineCardNewsRequest> cardNews,
            List<String> hashtags,
            List<Long> restaurantIds
    ) {
        return new UpdateMagazineRequest(
                "수정 제목", null, null, null, null, null,
                content, cardNews, hashtags, restaurantIds);
    }
}
