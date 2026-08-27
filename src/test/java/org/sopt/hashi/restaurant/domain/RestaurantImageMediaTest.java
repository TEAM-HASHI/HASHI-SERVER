package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

    private RestaurantMenu menu(String imageKey) {
        return RestaurantMenu.create(
                "메뉴", "설명", imageKey,
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), false);
    }
}
