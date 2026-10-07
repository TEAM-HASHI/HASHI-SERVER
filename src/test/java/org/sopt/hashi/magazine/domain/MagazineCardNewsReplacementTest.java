package org.sopt.hashi.magazine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MagazineCardNewsReplacementTest {

    @Test
    void 처음_붙이는_카드뉴스는_보낸_순서대로_새_행이_되고_asset만_추가_대상으로_알린다() {
        UUID firstAssetId = UUID.randomUUID();
        UUID secondAssetId = UUID.randomUUID();
        Magazine magazine = magazine();

        CardNewsReplacement replacement = magazine.planCardNewsReplacement(List.of(
                asset(firstAssetId),
                legacy("magazines/card-2.jpg"),
                asset(secondAssetId)));
        magazine.replaceCardNews(replacement);

        assertThat(replacement.addedAssetIds()).containsExactly(firstAssetId, secondAssetId);
        assertThat(replacement.removedAssetIds()).isEmpty();
        assertThat(magazine.getOrderedCardNews())
                .extracting(MagazineCardNews::getImageAssetId, MagazineCardNews::getFileKey,
                        MagazineCardNews::getDisplayOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(firstAssetId, null, 1),
                        org.assertj.core.groups.Tuple.tuple(null, "magazines/card-2.jpg", 2),
                        org.assertj.core.groups.Tuple.tuple(secondAssetId, null, 3));
        assertThat(magazine.getCardNews())
                .allSatisfy(cardNews -> assertThat(cardNews.getMagazine()).isSameAs(magazine));
    }

    @Test
    void 계획만_세우면_애그리거트는_바뀌지_않는다() {
        UUID existingAssetId = UUID.randomUUID();
        Magazine magazine = magazineWith(asset(existingAssetId));

        CardNewsReplacement replacement = magazine.planCardNewsReplacement(List.of(
                asset(UUID.randomUUID())));

        assertThat(replacement.removedAssetIds()).containsExactly(existingAssetId);
        assertThat(magazine.getCardNews()).singleElement()
                .satisfies(cardNews -> {
                    assertThat(cardNews.getImageAssetId()).isEqualTo(existingAssetId);
                    assertThat(cardNews.getDisplayOrder()).isEqualTo(1);
                });
    }

    @Test
    void 같은_이미지의_행은_재사용해_순서만_바꾸고_빠진_행은_지운다() {
        UUID removedAssetId = UUID.randomUUID();
        UUID retainedAssetId = UUID.randomUUID();
        UUID addedAssetId = UUID.randomUUID();
        Magazine magazine = magazineWith(
                legacy("magazines/card-1.jpg"),
                asset(removedAssetId),
                asset(retainedAssetId),
                legacy("magazines/card-4.jpg"));
        List<MagazineCardNews> before = magazine.getOrderedCardNews();

        CardNewsReplacement replacement = magazine.planCardNewsReplacement(List.of(
                asset(retainedAssetId),
                legacy("magazines/card-new.jpg"),
                asset(addedAssetId),
                legacy("magazines/card-4.jpg")));
        magazine.replaceCardNews(replacement);

        assertThat(replacement.addedAssetIds()).containsExactly(addedAssetId);
        assertThat(replacement.removedAssetIds()).containsExactly(removedAssetId);
        List<MagazineCardNews> after = magazine.getOrderedCardNews();
        assertThat(after).hasSize(4);
        assertThat(after.get(0)).isSameAs(before.get(2));
        assertThat(after.get(1).getFileKey()).isEqualTo("magazines/card-new.jpg");
        assertThat(after.get(1).getId()).isNull();
        assertThat(after.get(2).getImageAssetId()).isEqualTo(addedAssetId);
        assertThat(after.get(3)).isSameAs(before.get(3));
        assertThat(after)
                .extracting(MagazineCardNews::getDisplayOrder)
                .containsExactly(1, 2, 3, 4);
        assertThat(magazine.getCardNews()).doesNotContain(before.get(0), before.get(1));
    }

    @Test
    void 같은_legacy_key가_여러_장이면_보낸_수만큼만_앞에서부터_재사용한다() {
        Magazine magazine = magazineWith(
                legacy("magazines/same.jpg"),
                legacy("magazines/same.jpg"));
        List<MagazineCardNews> before = magazine.getOrderedCardNews();

        magazine.replaceCardNews(magazine.planCardNewsReplacement(List.of(
                legacy("magazines/same.jpg"))));

        assertThat(magazine.getCardNews()).containsExactly(before.get(0));
    }

    @Test
    void 빈_목록으로_교체하면_모두_지우고_기존_asset을_제거_대상으로_알린다() {
        UUID firstAssetId = UUID.randomUUID();
        UUID secondAssetId = UUID.randomUUID();
        Magazine magazine = magazineWith(
                asset(firstAssetId),
                legacy("magazines/card-2.jpg"),
                asset(secondAssetId));

        CardNewsReplacement replacement = magazine.planCardNewsReplacement(List.of());
        magazine.replaceCardNews(replacement);

        assertThat(replacement.addedAssetIds()).isEmpty();
        assertThat(replacement.removedAssetIds()).containsExactly(firstAssetId, secondAssetId);
        assertThat(magazine.getCardNews()).isEmpty();
    }

    @Test
    void 카드뉴스_출처는_key와_asset_중_정확히_하나여야_한다() {
        assertThatThrownBy(() -> new CardNewsSource(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CardNewsSource("magazines/card.jpg", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CardNewsSource(" ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Magazine magazine() {
        return Magazine.create(
                "카드뉴스 매거진", "magazines/banner.jpg", null, "magazines/thumbnail.jpg", null,
                "https://www.instagram.com/p/test/", null);
    }

    private CardNewsSource legacy(String fileKey) {
        return new CardNewsSource(fileKey, null);
    }

    private CardNewsSource asset(UUID imageAssetId) {
        return new CardNewsSource(null, imageAssetId);
    }

    // 저장된 카드뉴스처럼 보이게 id를 11부터 매긴다
    private Magazine magazineWith(CardNewsSource... sources) {
        Magazine magazine = magazine();
        magazine.replaceCardNews(magazine.planCardNewsReplacement(List.of(sources)));
        List<MagazineCardNews> attached = magazine.getOrderedCardNews();
        for (int index = 0; index < attached.size(); index++) {
            ReflectionTestUtils.setField(attached.get(index), "id", 11L + index);
        }
        return magazine;
    }
}
