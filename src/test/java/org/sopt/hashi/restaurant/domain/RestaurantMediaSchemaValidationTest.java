package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@Transactional
class RestaurantMediaSchemaValidationTest {

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void asset_ID_컬럼은_모듈_FK없이_ASCII_UUID와_local_unique를_사용한다() {
        assertAssetColumn("restaurant_image", "uq_restaurant_image_asset_id");
        assertAssetColumn("restaurant_menu", "uq_restaurant_menu_asset_id");

        Integer mediaForeignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.key_column_usage
                WHERE constraint_schema = DATABASE()
                  AND table_name IN ('restaurant_image', 'restaurant_menu')
                  AND referenced_table_name = 'image_asset'
                """, Integer.class);
        assertThat(mediaForeignKeys).isZero();
    }

    @Test
    void 식당_이미지는_source가_필수이고_메뉴는_이미지_없음을_허용한다() {
        Restaurant restaurant = restaurantRepository.saveAndFlush(createRestaurant());
        UUID restaurantAssetId = UUID.randomUUID();
        UUID menuAssetId = UUID.randomUUID();

        assertThat(insertRestaurantImage(
                restaurant.getId(), null, restaurantAssetId, 1)).isEqualTo(1);
        assertThatThrownBy(() -> insertRestaurantImage(
                restaurant.getId(), null, restaurantAssetId, 2))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRestaurantImage(
                restaurant.getId(), " ", UUID.randomUUID(), 2))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRestaurantImage(
                restaurant.getId(), null, null, 2))
                .isInstanceOf(DataAccessException.class);

        assertThat(insertMenu(restaurant.getId(), "이미지 없음 1", null)).isEqualTo(1);
        assertThat(insertMenu(restaurant.getId(), "이미지 없음 2", null)).isEqualTo(1);
        assertThat(insertMenu(restaurant.getId(), "asset 메뉴", menuAssetId)).isEqualTo(1);
        assertThatThrownBy(() -> insertMenu(
                restaurant.getId(), "asset 중복 메뉴", menuAssetId))
                .isInstanceOf(DataAccessException.class);
    }

    private void assertAssetColumn(String tableName, String indexName) {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                SELECT character_maximum_length,
                       character_set_name,
                       collation_name,
                       is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = 'image_asset_id'
                """, tableName);
        Integer uniqueIndexColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                  AND non_unique = 0
                  AND column_name = 'image_asset_id'
                """, Integer.class, tableName, indexName);

        assertThat(column.get("character_maximum_length")).isEqualTo(36L);
        assertThat(column.get("character_set_name")).isEqualTo("ascii");
        assertThat(column.get("collation_name")).isEqualTo("ascii_bin");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(uniqueIndexColumns).isEqualTo(1);
    }

    private int insertRestaurantImage(
            Long restaurantId,
            String fileKey,
            UUID assetId,
            int displayOrder
    ) {
        return jdbcTemplate.update("""
                INSERT INTO restaurant_image (
                    restaurant_id, file_key, image_asset_id, display_order,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, restaurantId, fileKey, assetId == null ? null : assetId.toString(), displayOrder);
    }

    private int insertMenu(Long restaurantId, String name, UUID assetId) {
        return jdbcTemplate.update("""
                INSERT INTO restaurant_menu (
                    restaurant_id, name, description, image_key, image_asset_id,
                    price_currency, price_amount, is_main, created_at, updated_at
                ) VALUES (?, ?, '설명', NULL, ?, 'JPY', 1000, FALSE,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, restaurantId, name, assetId == null ? null : assetId.toString());
    }

    private Restaurant createRestaurant() {
        return Restaurant.create(
                "스키마 검증 식당",
                "schema restaurant",
                "식당 소개",
                "식당 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                "초밥",
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000)
        );
    }
}
