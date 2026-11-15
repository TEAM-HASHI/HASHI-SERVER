package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Release V14 to V45 SQL compatibility only; not a live-app or production deployment test. */
@Testcontainers
class ProductionTransitionMigrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.9")
            .withDatabaseName("prod_transition_synthetic")
            .withUsername("rehearsal")
            .withPassword("local-test-only")
            .withLabel("hashi.task", "prod-transition-288");

    private static final List<String> PRESERVED = List.of(
            "SELECT id, name, address, rating, review_count, deleted FROM restaurant ORDER BY id",
            "SELECT id, restaurant_id, file_key, display_order FROM restaurant_image ORDER BY id",
            "SELECT id, restaurant_id, name, image_key FROM restaurant_menu ORDER BY id",
            "SELECT id, banner_key, thumbnail_key, deleted FROM magazine ORDER BY id",
            "SELECT id, profile_image_key, deleted FROM users ORDER BY id",
            "SELECT id, review_id, file_key, display_order FROM review_image ORDER BY id");
    private static final List<List<List<String>>> before = new ArrayList<>();

    @BeforeAll
    static void 합성_V14_데이터를_최신_스키마로_올린다() throws SQLException {
        Flyway v14 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("14"))
                .cleanDisabled(false).load();
        v14.clean();
        v14.migrate();
        execute(restaurantInsert(101, false));
        execute("INSERT INTO restaurant_image(id,restaurant_id,file_key,display_order) VALUES(301,101,'synthetic/restaurant.jpg',0)");
        execute("INSERT INTO restaurant_menu(id,restaurant_id,name,description,image_key,price_currency,price_amount,is_main) VALUES(201,101,'fixture menu','fixture','synthetic/menu.jpg','JPY',1000,TRUE)");
        execute("INSERT INTO users(id,nickname,birth_date,phone,email,profile_image_key) VALUES(401,'fixture-user','2000-01-01','000-0000-0000','fixture@example.invalid','synthetic/profile.jpg')");
        execute(magazineInsert(501));
        execute("INSERT INTO review(id,reservation_id,restaurant_id,user_id,rating,content,deleted) VALUES(601,901,101,401,5,'synthetic review',FALSE)");
        execute("INSERT INTO review_image(id,review_id,file_key,display_order) VALUES(701,601,'synthetic/review.jpg',0)");
        for (String sql : PRESERVED) before.add(rows(sql));
        releaseTarget().migrate();
    }

    @Test
    void V14에서_V45로_변경해도_기존_사진키와_ID는_보존된다() throws SQLException {
        for (int i = 0; i < PRESERVED.size(); i++) {
            // Other tests use different IDs; filter the initial fixture set for deterministic order independence.
            String query = PRESERVED.get(i).replace(" ORDER BY id", " WHERE id IN (101,201,301,401,501,701) ORDER BY id");
            assertThat(rows(query)).isEqualTo(before.get(i));
        }
        assertThat(releaseTarget().info().current().getVersion().getVersion()).isEqualTo("45");
        assertThat(releaseTarget().validateWithResult().validationSuccessful).isTrue();
        assertThat(rows("SELECT place_type FROM restaurant WHERE id=101")).isEqualTo(List.of(List.of("RESTAURANT")));
        assertThat(rows("SELECT like_count FROM meta_magazine WHERE magazine_id=501")).isEqualTo(List.of(List.of("0")));
    }

    @Test
    void 이미지_제어행은_최초_비활성_v1이며_기존_파일을_자동변환하지_않는다() throws SQLException {
        assertThat(rows("SELECT current_spec_version,issuance_enabled,lock_version FROM media_pipeline_config WHERE id=1"))
                .isEqualTo(List.of(List.of("1", "0", "0")));
        assertThat(rows("SELECT COUNT(*) FROM image_asset")).isEqualTo(List.of(List.of("0")));
        assertThat(rows("SELECT COUNT(*) FROM image_rendition")).isEqualTo(List.of(List.of("0")));
        assertThat(rows("SELECT COUNT(*) FROM restaurant_geocoding_budget WHERE enabled=TRUE")).isEqualTo(List.of(List.of("0")));
        assertThat(rows("SELECT COUNT(*) FROM restaurant_places_budget WHERE enabled=TRUE")).isEqualTo(List.of(List.of("0")));
    }

    @Test
    void 구앱의_place_type_없는_식당등록은_V26_이후_거절되지만_새_형식은_성공한다() throws SQLException {
        assertThatThrownBy(() -> execute(restaurantInsert(102, false)))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1364));
        execute(restaurantInsert(102, true));
        assertThat(rows("SELECT place_type FROM restaurant WHERE id=102")).isEqualTo(List.of(List.of("RESTAURANT")));
    }

    @Test
    void V27_이후_구앱이_추가한_매거진에는_집계행이_자동으로_생기지_않는다() throws SQLException {
        execute(magazineInsert(502));
        assertThat(rows("SELECT COUNT(*) FROM meta_magazine WHERE magazine_id=502")).isEqualTo(List.of(List.of("0")));
        assertThat(execute("UPDATE meta_magazine SET like_count=like_count+1 WHERE magazine_id=502")).isZero();
    }

    @Test
    void 기존_사진키_방식의_식당_메뉴_프로필_리뷰_쓰기_제약은_유지된다() throws SQLException {
        execute("INSERT INTO restaurant_image(id,restaurant_id,file_key,display_order) VALUES(302,101,'synthetic/new-restaurant.jpg',1)");
        execute("INSERT INTO restaurant_menu(id,restaurant_id,name,description,image_key,price_currency,price_amount,is_main) VALUES(202,101,'new fixture menu','fixture','synthetic/new-menu.jpg','JPY',1000,FALSE)");
        execute("INSERT INTO users(id,nickname,birth_date,phone,email,profile_image_key) VALUES(402,'fixture-new','2000-01-01','000-0000-0001','new-fixture@example.invalid','synthetic/new-profile.jpg')");
        execute("INSERT INTO review_image(id,review_id,file_key,display_order) VALUES(702,601,'synthetic/new-review.jpg',1)");
        assertThat(rows("SELECT image_asset_id IS NULL FROM restaurant_image WHERE id=302")).isEqualTo(List.of(List.of("1")));
        assertThat(rows("SELECT image_asset_id IS NULL FROM restaurant_menu WHERE id=202")).isEqualTo(List.of(List.of("1")));
        assertThat(rows("SELECT image_asset_id IS NULL FROM review_image WHERE id=702")).isEqualTo(List.of(List.of("1")));
    }

    @Test
    void DDL_일부_성공후_실패하면_rollback으로_되돌아가지_않고_남은_변경을_별도_적용해야_한다()
            throws SQLException {
        // Isolated probe table: no application table or Flyway history is repaired in this test.
        execute("CREATE TABLE transition_ddl_probe (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        execute("INSERT INTO transition_ddl_probe(id) VALUES(1)");
        try {
            try (Connection connection = connection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate("ALTER TABLE transition_ddl_probe ADD COLUMN retained_value INT NULL");
                assertThatThrownBy(() -> statement.executeUpdate(
                        "ALTER TABLE transition_ddl_probe ADD COLUMN retained_value INT NULL"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1060));
                connection.rollback();
            }
            assertThat(rows("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='transition_ddl_probe'
                      AND column_name='retained_value'
                    """)).isEqualTo(List.of(List.of("1")));
            assertThat(rows("SELECT id FROM transition_ddl_probe")).isEqualTo(List.of(List.of("1")));

            // Only the missing forward step is applied; this is not an automated recovery recipe.
            execute("ALTER TABLE transition_ddl_probe ADD COLUMN remaining_value INT NULL");
            assertThat(rows("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='transition_ddl_probe'
                      AND column_name IN ('retained_value','remaining_value')
                    """)).isEqualTo(List.of(List.of("2")));
            assertThat(rows("SELECT id FROM transition_ddl_probe")).isEqualTo(List.of(List.of("1")));
            assertThat(releaseTarget().validateWithResult().validationSuccessful).isTrue();
        } finally {
            execute("DROP TABLE transition_ddl_probe");
        }
    }

    private static String restaurantInsert(long id, boolean includePlaceType) {
        return "INSERT INTO restaurant(id,name,local_name,address,area,genre,food_category,summary,description,price_currency,price_min,price_max,deleted"
                + (includePlaceType ? ",place_type" : "") + ") VALUES(" + id
                + ",'fixture restaurant','fixture local','synthetic address','GINZA','SUSHI','fixture food','fixture summary','fixture description','JPY',1000,2000,FALSE"
                + (includePlaceType ? ",'RESTAURANT'" : "") + ")";
    }

    private static String magazineInsert(long id) {
        return "INSERT INTO magazine(id,title,banner_key,thumbnail_key,instagram_redirect_url,deleted) VALUES("
                + id + ",'fixture magazine','synthetic/banner.jpg','synthetic/thumbnail.jpg','https://example.invalid/fixture',FALSE)";
    }

    private static Flyway releaseTarget() {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("45"))
                .validateOnMigrate(true).baselineOnMigrate(false).load();
    }

    private static int execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static List<List<String>> rows(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            List<List<String>> values = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                values.add(row);
            }
            return values;
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
