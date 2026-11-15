package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ProfileMagazineMediaUpgradeTest {

    @Container
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_upgrade")
            .withUsername("hashi")
            .withPassword("hashi");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpV19() {
        flyway("19").migrate();
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    }

    @Test
    void V19의_기존_이미지를_보존하고_V20과_V21로_순차_이행한다() {
        insertUser(1, "profiles/legacy.jpg");
        insertUser(2, "");
        insertUser(3, "   ");
        insertUser(4, null);
        insertMagazine("magazines/banner.jpg", "magazines/thumbnail.jpg");

        Flyway v20 = flyway("20");
        assertThat(v20.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbcTemplate.queryForList(
                "SELECT profile_image_key FROM users ORDER BY id", String.class))
                .containsExactly("profiles/legacy.jpg", null, null, null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE profile_image_asset_id IS NOT NULL", Integer.class))
                .isZero();
        Flyway v21 = flyway("21");
        assertThat(v21.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT banner_key, thumbnail_key, banner_image_asset_id, thumbnail_image_asset_id
                FROM magazine
                """))
                .containsEntry("banner_key", "magazines/banner.jpg")
                .containsEntry("thumbnail_key", "magazines/thumbnail.jpg")
                .containsEntry("banner_image_asset_id", null)
                .containsEntry("thumbnail_image_asset_id", null);
        assertThat(v21.validateWithResult().validationSuccessful).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"banner", "thumbnail"})
    void 빈_매거진_이미지는_V20을_보존하고_V21만_복구한다(String blankSlot) {
        insertUser(1, "profiles/legacy.jpg");
        String bannerKey = blankSlot.equals("banner") ? " " : "magazines/banner.jpg";
        String thumbnailKey = blankSlot.equals("thumbnail") ? " " : "magazines/thumbnail.jpg";
        insertMagazine(bannerKey, thumbnailKey);

        Flyway v20 = flyway("20");
        assertThat(v20.migrate().migrationsExecuted).isEqualTo(1);

        Flyway v21 = flyway("21");
        assertThatThrownBy(v21::migrate)
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("ck_v21_magazine_media_guard");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'users'
                  AND column_name = 'profile_image_asset_id'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'magazine'
                  AND column_name IN ('banner_image_asset_id', 'thumbnail_image_asset_id')
                """, Integer.class)).isZero();
        assertThat(successfulMigrationCount("20")).isEqualTo(1);
        assertThat(successfulMigrationCount("21")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT profile_image_key FROM users", String.class)).isEqualTo("profiles/legacy.jpg");
        assertThat(jdbcTemplate.queryForMap("SELECT banner_key, thumbnail_key FROM magazine"))
                .containsEntry("banner_key", bannerKey)
                .containsEntry("thumbnail_key", thumbnailKey);

        String validKey = "magazines/recovered.jpg";
        jdbcTemplate.update(
                "UPDATE magazine SET " + blankSlot + "_key = ?",
                validKey
        );
        v21.repair();
        assertThat(v21.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(successfulMigrationCount("21")).isEqualTo(1);
    }

    private Integer successfulMigrationCount(String version) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = ?
                  AND success = TRUE
                """, Integer.class, version);
    }

    private Flyway flyway(String targetVersion) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .target(targetVersion)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    private void insertUser(int sequence, String profileImageKey) {
        jdbcTemplate.update("""
                INSERT INTO users (
                    nickname, name_eng, birth_date, phone, email,
                    profile_image_key, deleted, created_at, updated_at
                ) VALUES (?, 'HASHI USER', '1998-01-01', ?, ?, ?, FALSE,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "기존회원" + sequence, "0102000000" + sequence,
                "legacy-profile-" + sequence + "@hashi.test", profileImageKey);
    }

    private void insertMagazine(String bannerKey, String thumbnailKey) {
        jdbcTemplate.update("""
                INSERT INTO magazine (
                    title, banner_key, thumbnail_key, instagram_redirect_url,
                    deleted, created_at, updated_at
                ) VALUES ('기존 매거진', ?, ?, 'https://www.instagram.com/p/legacy-media/',
                          FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, bannerKey, thumbnailKey);
    }
}
