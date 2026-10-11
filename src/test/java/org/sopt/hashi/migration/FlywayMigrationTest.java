package org.sopt.hashi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Test
    void 빈_MySQL_스키마에_전체_마이그레이션을_적용한다() {
        Flyway flyway = flyway();
        flyway.clean();

        flyway.migrate();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void V37의_식당과_컬렉션을_보존하고_지도_외부호출은_비활성으로_추가한다() throws SQLException {
        Flyway throughV37 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("37"))
                .cleanDisabled(false).load();
        throughV37.clean();
        throughV37.migrate();
        execute("""
                INSERT INTO restaurant (id, name, local_name, address, area, genre, food_category,
                    place_type, summary, description, price_currency, price_min, price_max,
                    rating, rating_sum, review_count, deleted)
                VALUES (101, '보존 식당', '保存食堂', '東京都中央区月島3-16-9 1F',
                    'GINZA', 'JAPANESE', 'JAPANESE', 'RESTAURANT', '요약', '설명',
                    'JPY', 1000, 3000, 4.5, 9, 2, FALSE),
                    (102, '삭제 식당', '削除食堂', '1 Chome-9-1 Marunouchi, Tokyo',
                    'GINZA', 'JAPANESE', 'JAPANESE', 'RESTAURANT', '요약', '설명',
                    'JPY', 1000, 2000, 0, 0, 0, TRUE)
                """);
        execute("""
                INSERT INTO restaurant_menu (id, restaurant_id, name, description,
                    price_currency, price_amount, is_main)
                VALUES (201, 101, '정식', '메뉴 설명', 'JPY', 1500, TRUE)
                """);
        execute("INSERT INTO restaurant_hashtag VALUES (101, '혼밥')");
        execute("""
                INSERT INTO restaurant_collection (id, user_id, name, color, visibility)
                VALUES (301, 901, ' 여행 식당 ', 'BLUE', 'PRIVATE')
                """);
        execute("INSERT INTO saved_restaurant (id, collection_id, restaurant_id) VALUES (401, 301, 101)");
        List<String> preservedQueries = List.of(
                "SELECT id, name, local_name, address, rating, rating_sum, review_count, deleted FROM restaurant ORDER BY id",
                "SELECT * FROM restaurant_menu ORDER BY id",
                "SELECT * FROM restaurant_hashtag ORDER BY restaurant_id, hashtag",
                "SELECT id, user_id, name, color, description, visibility, created_at, updated_at FROM restaurant_collection ORDER BY id",
                "SELECT * FROM saved_restaurant ORDER BY id");
        List<List<List<String>>> before = new ArrayList<>();
        for (String query : preservedQueries) {
            before.add(rows(query));
        }

        Flyway upgraded = flyway();
        upgraded.migrate();

        for (int index = 0; index < preservedQueries.size(); index++) {
            assertThat(rows(preservedQueries.get(index))).isEqualTo(before.get(index));
        }
        assertThat(rows("SELECT COUNT(*) FROM restaurant WHERE location_id IS NOT NULL OR map_region_id IS NOT NULL OR geocoding_address IS NOT NULL"))
                .isEqualTo(List.of(List.of("0")));
        assertThat(rows("SELECT collection_version FROM restaurant_collection WHERE id = 301"))
                .isEqualTo(List.of(List.of("0")));
        for (String table : List.of("restaurant_location", "restaurant_location_job", "map_region",
                "restaurant_location_maintenance_run")) {
            assertThat(rows("SELECT COUNT(*) FROM " + table)).isEqualTo(List.of(List.of("0")));
        }
        assertThat(rows("SELECT COUNT(*) FROM restaurant_geocoding_budget WHERE enabled = TRUE"))
                .isEqualTo(List.of(List.of("0")));
        assertThat(rows("SELECT COUNT(*) FROM restaurant_places_budget WHERE enabled = TRUE"))
                .isEqualTo(List.of(List.of("0")));
        assertThat(upgraded.info().pending()).isEmpty();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void V17은_예상하지_않은_PROCESSING_row가_있으면_부분_DDL_없이_실패한다()
            throws SQLException {
        Flyway throughV15 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("15"))
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(false)
                .load();
        throughV15.clean();
        throughV15.migrate();
        insertUnexpectedProcessingRow();

        assertThatThrownBy(() -> flyway().migrate())
                .isInstanceOf(FlywayException.class);

        assertThat(columnExists("image_asset", "target_processing_started_at")).isFalse();
        assertThat(columnExists("image_asset", "last_recovery_requested_at")).isFalse();
        assertThat(columnExists("image_asset", "processing_recovery_attempts")).isFalse();
    }

    @Test
    void V18과_V19는_테이블별_복구_경계를_분리한다() throws SQLException {
        Flyway throughV18 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("18"))
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(false)
                .load();
        throughV18.clean();
        throughV18.migrate();

        assertThat(columnExists("restaurant_image", "image_asset_id")).isTrue();
        assertThat(columnExists("restaurant_menu", "image_asset_id")).isFalse();
        assertThat(migrationSucceeded("18")).isTrue();

        execute("ALTER TABLE restaurant_menu ADD COLUMN image_asset_id VARCHAR(10) NULL");
        Flyway remaining = flyway();
        assertThatThrownBy(remaining::migrate).isInstanceOf(FlywayException.class);

        assertThat(columnExists("restaurant_image", "image_asset_id")).isTrue();
        assertThat(migrationSucceeded("18")).isTrue();

        execute("ALTER TABLE restaurant_menu DROP COLUMN image_asset_id");
        remaining.repair();
        remaining.migrate();

        assertThat(columnExists("restaurant_menu", "image_asset_id")).isTrue();
        assertThat(migrationSucceeded("19")).isTrue();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(false)
                .load();
    }

    private void insertUnexpectedProcessingRow() throws SQLException {
        UUID assetId = UUID.randomUUID();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO image_asset (
                         public_id, purpose, creation_origin,
                         creator_actor_type, creator_subject_id,
                         owner_actor_type, owner_subject_id,
                         original_object_key, declared_content_type, declared_bytes,
                         upload_expires_at, source_version_id, source_etag,
                         processing_status, binding_status,
                         target_spec_version, target_spec_digest,
                         target_processing_status, current_job_id,
                         last_issued_spec_version, cleanup_status, lock_version,
                         created_at, updated_at
                     ) VALUES (
                         ?, 'REVIEW', 'DIRECT_UPLOAD',
                         'USER', 1, 'USER', 1,
                         ?, 'image/jpeg', 1024,
                         CURRENT_TIMESTAMP(6), 'version-1', '"etag-1"',
                         'PROCESSING', 'UNBOUND',
                         1, ?, 'PROCESSING', ?,
                         1, 'ACTIVE', 0,
                         CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                     )
                     """)) {
            statement.setString(1, assetId.toString());
            statement.setString(2, "media/originals/%s/original".formatted(assetId));
            statement.setString(3,
                    "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f");
            statement.setString(4, UUID.randomUUID().toString());
            statement.executeUpdate();
        }
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = ?
                       AND column_name = ?
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private boolean migrationSucceeded(String version) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM flyway_schema_history
                     WHERE version = ?
                       AND success = TRUE
                     """)) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<List<String>> rows(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<List<String>> rows = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    row.add(result.getString(column));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
