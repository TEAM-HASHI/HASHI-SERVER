package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RestaurantImageMediaTest {

    @Test
    void 식당_이미지는_legacy_asset_backfill_source를_각각_표현한다() {
        UUID assetId = UUID.randomUUID();

        RestaurantImage legacy = RestaurantImage.createLegacy("restaurants/1.jpg", 1);
        RestaurantImage asset = RestaurantImage.createAsset(assetId, 2);
        RestaurantImage backfilled =
                RestaurantImage.createBackfilled("restaurants/3.jpg", assetId, 3);

        assertThat(legacy.getFileKey()).isEqualTo("restaurants/1.jpg");
        assertThat(legacy.getImageAssetId()).isNull();
        assertThat(asset.getFileKey()).isNull();
        assertThat(asset.getImageAssetId()).isEqualTo(assetId);
        assertThat(backfilled.getFileKey()).isEqualTo("restaurants/3.jpg");
        assertThat(backfilled.getImageAssetId()).isEqualTo(assetId);
    }

    @Test
    void 식당_이미지는_source와_양수_순서가_필수다() {
        assertThatThrownBy(() -> RestaurantImage.createLegacy(" ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestaurantImage.createAsset(null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RestaurantImage.createLegacy("restaurants/1.jpg", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 메뉴는_legacy_asset_또는_이미지_없음을_표현한다() {
        UUID assetId = UUID.randomUUID();

        RestaurantMenu legacy = menu("menu/legacy.jpg");
        RestaurantMenu asset = RestaurantMenu.createWithAsset(
                "asset 메뉴", "설명", assetId,
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), false);
        RestaurantMenu noImage = menu(null);

        assertThat(legacy.getImageKey()).isEqualTo("menu/legacy.jpg");
        assertThat(legacy.getImageAssetId()).isNull();
        assertThat(asset.getImageKey()).isNull();
        assertThat(asset.getImageAssetId()).isEqualTo(assetId);
        assertThat(noImage.getImageKey()).isNull();
        assertThat(noImage.getImageAssetId()).isNull();
    }

    @Test
    void backfill은_기존_association과_legacy_정보를_보존하고_asset만_연결한다() {
        Restaurant restaurant = restaurant();
        RestaurantImage image = RestaurantImage.createLegacy("restaurants/legacy.jpg", 3);
        RestaurantMenu menu = menu("menus/legacy.jpg");
        ReflectionTestUtils.setField(image, "id", 11L);
        ReflectionTestUtils.setField(menu, "id", 21L);
        restaurant.replaceImages(List.of(image));
        restaurant.replaceMenus(List.of(menu));
        UUID imageAssetId = UUID.randomUUID();
        UUID menuAssetId = UUID.randomUUID();

        assertThat(restaurant.attachBackfilledImage(
                11L, "restaurants/legacy.jpg", imageAssetId)).isTrue();
        assertThat(restaurant.attachBackfilledMenuImage(
                21L, "menus/legacy.jpg", menuAssetId)).isTrue();

        assertThat(image.getId()).isEqualTo(11L);
        assertThat(image.getFileKey()).isEqualTo("restaurants/legacy.jpg");
        assertThat(image.getDisplayOrder()).isEqualTo(3);
        assertThat(image.getImageAssetId()).isEqualTo(imageAssetId);
        assertThat(menu.getId()).isEqualTo(21L);
        assertThat(menu.getImageKey()).isEqualTo("menus/legacy.jpg");
        assertThat(menu.getName()).isEqualTo("메뉴");
        assertThat(menu.getImageAssetId()).isEqualTo(menuAssetId);
    }

    @Test
    void backfill은_동시에_바뀐_key나_이미_연결된_association을_덮어쓰지_않는다() {
        Restaurant restaurant = restaurant();
        RestaurantImage image = RestaurantImage.createLegacy("restaurants/current.jpg", 1);
        RestaurantMenu menu = RestaurantMenu.createWithAsset(
                "asset 메뉴", "설명", UUID.randomUUID(),
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), false);
        ReflectionTestUtils.setField(image, "id", 11L);
        ReflectionTestUtils.setField(menu, "id", 21L);
        restaurant.replaceImages(List.of(image));
        restaurant.replaceMenus(List.of(menu));

        assertThat(restaurant.attachBackfilledImage(
                11L, "restaurants/stale.jpg", UUID.randomUUID())).isFalse();
        assertThat(restaurant.attachBackfilledMenuImage(
                21L, "menus/legacy.jpg", UUID.randomUUID())).isFalse();

        assertThat(image.getImageAssetId()).isNull();
        assertThat(menu.getImageAssetId()).isNotNull();
    }

    private RestaurantMenu menu(String imageKey) {
        return RestaurantMenu.create(
                "메뉴", "설명", imageKey,
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), false);
    }

    private Restaurant restaurant() {
        return Restaurant.create(
                "이미지 식당", "image restaurant", "소개", "상세 설명",
                "도쿄도", "도쿄", RestaurantGenre.SUSHI, "초밥",
                RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000));
    }
}
