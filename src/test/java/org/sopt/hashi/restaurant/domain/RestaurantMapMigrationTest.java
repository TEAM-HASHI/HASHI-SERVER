package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RestaurantMapMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_map_upgrade").withUsername("hashi").withPassword("hashi");

    private JdbcTemplate jdbc;

    @BeforeEach
    void 이전_스키마를_준비한다() {
        Flyway baseline = flyway("27");
        baseline.clean();
        baseline.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void 기존_활성_삭제_식당과_하위_데이터는_보존하고_위치와_지역을_자동_생성하지_않는다() {
        jdbc.update("""
                INSERT INTO restaurant (id, name, local_name, summary, description, address, area,
                    genre, food_category, place_type, price_currency, price_min, price_max,
                    rating_sum, review_count, rating, deleted, created_at, updated_at)
                VALUES (1, 'fixture active', 'fixture', 'summary', 'description', 'synthetic address', 'area',
                    'SUSHI', 'food', 'RESTAURANT', 'JPY', 100, 200, 9, 2, 4.5, FALSE, NOW(6), NOW(6)),
                    (2, 'fixture deleted', 'fixture', 'summary', 'description', 'old address', 'area',
                    'SUSHI', 'food', 'CAFE', 'JPY', 100, 200, 0, 0, 0.0, TRUE, NOW(6), NOW(6))
                """);
        jdbc.update("INSERT INTO restaurant_hashtag (restaurant_id, hashtag) VALUES (1, 'fixture')");
        jdbc.update("""
                INSERT INTO restaurant_image (restaurant_id, file_key, display_order)
                VALUES (1, 'fixtures/map-test.jpg', 1)
                """);
        jdbc.update("""
                INSERT INTO restaurant_menu (restaurant_id, name, description, price_currency, price_amount, is_main)
                VALUES (1, 'fixture menu', 'description', 'JPY', 100, TRUE)
                """);
        List<Map<String, Object>> restaurants = jdbc.queryForList("SELECT * FROM restaurant ORDER BY id");
        List<Map<String, Object>> images = jdbc.queryForList("SELECT * FROM restaurant_image");
        List<Map<String, Object>> menus = jdbc.queryForList("SELECT * FROM restaurant_menu");
        List<Map<String, Object>> hashtags = jdbc.queryForList("SELECT * FROM restaurant_hashtag");

        Flyway migration = flyway("28");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        List<Map<String, Object>> upgraded = jdbc.queryForList("SELECT * FROM restaurant ORDER BY id");
        assertThat(upgraded).hasSize(2);
        for (int index = 0; index < restaurants.size(); index++) {
            assertThat(upgraded.get(index)).containsAllEntriesOf(restaurants.get(index))
                    .containsEntry("location_id", null).containsEntry("map_region_id", null);
        }
        assertThat(jdbc.queryForList("SELECT * FROM restaurant_image")).isEqualTo(images);
        assertThat(jdbc.queryForList("SELECT * FROM restaurant_menu")).isEqualTo(menus);
        assertThat(jdbc.queryForList("SELECT * FROM restaurant_hashtag")).isEqualTo(hashtags);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_location", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM map_region", Integer.class)).isZero();
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        validateUpgradedSchema();
        jdbc.update("UPDATE restaurant SET map_region_id=123 WHERE id=1");
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT map_region_id FROM restaurant WHERE id=1", Long.class))
                .isEqualTo(123L);
    }

    @Test
    void 좌표_쌍과_READY_출처_수명과_재시도_제약은_SQL_우회도_차단한다() {
        flyway("28").migrate();
        jdbc.update("""
                INSERT INTO restaurant_location (id, status, address_revision, request_id, lock_version)
                VALUES (1, 'PENDING', 1, '00000000-0000-0000-0000-000000000001', 0)
                """);
        for (String change : List.of(
                "latitude=0", "longitude=0", "status='READY'", "status='UNKNOWN'", "status='pending'",
                "status='PENDING '", "address_revision=0",
                "lock_version=-1", "source='OPERATOR'", "obtained_at=NOW(6)", "valid_until=NOW(6)",
                "status='RETRY_WAIT'", "next_attempt_at=NOW(6)")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE restaurant_location SET " + change + " WHERE id=1"))
                    .as(change).isInstanceOf(DataAccessException.class);
        }
        jdbc.update("""
                UPDATE restaurant_location SET status='READY', latitude=0, longitude=0,
                    source='GOOGLE_GEOCODING', obtained_at='2026-01-01 00:00:00',
                    valid_until='2026-01-02 00:00:00' WHERE id=1
                """);
        for (String change : List.of(
                "latitude=NULL", "longitude=NULL", "latitude=90.000001", "longitude=-180.000001",
                "source=NULL", "source='UNKNOWN'", "source='operator'", "source='OPERATOR '",
                "obtained_at=NULL", "valid_until=NULL",
                "valid_until=obtained_at", "status='FAILED'", "next_attempt_at=NOW(6)")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE restaurant_location SET " + change + " WHERE id=1"))
                    .as(change).isInstanceOf(DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT latitude FROM restaurant_location", java.math.BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    @Test
    void 지역_범위와_대표_위치와_코드_유일성을_검증한다() {
        flyway("28").migrate();
        jdbc.update("""
                INSERT INTO map_region (id, code, name, cluster_latitude, cluster_longitude,
                    south, north, west, east, display_order) VALUES (1, 'FIXTURE', 'region', 10, 20, 10, 11, 20, 21, 0)
                """);
        for (String change : List.of("south=north", "west=east", "north=91", "east=181",
                "cluster_latitude=12", "cluster_longitude=19", "display_order=-1", "name=' '", "code='lower'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE map_region SET " + change + " WHERE id=1"))
                    .as(change).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO map_region (code, name, cluster_latitude, cluster_longitude,
                    south, north, west, east, display_order) VALUES ('FIXTURE', 'other', 10, 20, 10, 11, 20, 21, 1)
                """)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT active FROM map_region WHERE id=1", Boolean.class)).isFalse();
    }

    @Test
    void FK는_식당과_소유_위치에만_걸고_좌표의_정밀도와_인덱스를_고정한다() {
        flyway("28").migrate();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE constraint_schema=DATABASE() AND referenced_table_name='map_region'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE constraint_schema=DATABASE() AND table_name='restaurant'
                    AND referenced_table_name='restaurant_location' AND column_name='location_id'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='restaurant' AND column_name='location_id'
                    AND non_unique=0
                """, Integer.class)).isEqualTo(1);
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='restaurant_location'
                    AND column_name IN ('latitude', 'longitude') ORDER BY column_name
                """);
        assertThat(columns).hasSize(2);
        assertThat(((Number) columns.getFirst().get("numeric_precision")).intValue()).isEqualTo(9);
        assertThat(((Number) columns.getLast().get("numeric_precision")).intValue()).isEqualTo(10);
        for (Map<String, Object> column : columns) {
            assertThat(((Number) column.get("numeric_scale")).intValue()).isEqualTo(6);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(target).validateOnMigrate(true)
                .baselineOnMigrate(false).cleanDisabled(false).load();
    }

    private void validateUpgradedSchema() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", MYSQL.getJdbcUrl())
                .applySetting("hibernate.connection.username", MYSQL.getUsername())
                .applySetting("hibernate.connection.password", MYSQL.getPassword())
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            try (var sessionFactory = new MetadataSources(registry)
                    .addAnnotatedClass(Restaurant.class)
                    .addAnnotatedClass(RestaurantLocation.class)
                    .addAnnotatedClass(MapRegion.class)
                    .addAnnotatedClass(RestaurantMenu.class)
                    .addAnnotatedClass(RestaurantImage.class)
                    .addAnnotatedClass(RestaurantBusinessHour.class)
                    .buildMetadata().buildSessionFactory()) {
                assertThat(sessionFactory.isOpen()).isTrue();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
