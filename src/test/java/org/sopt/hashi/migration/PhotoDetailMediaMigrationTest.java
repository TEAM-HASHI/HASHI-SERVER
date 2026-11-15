package org.sopt.hashi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PhotoDetailMediaMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_photo_migration");

    @Test
    void v44은_기존_파생본과_발급설정을_유지하며_사진_role만_허용한다() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("42").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        var previousConfig = jdbc.queryForMap("SELECT * FROM media_pipeline_config WHERE id = 1");
        jdbc.update("""
                INSERT INTO image_asset (public_id, purpose, creation_origin,
                    creator_actor_type, creator_subject_id, owner_actor_type, owner_subject_id,
                    original_object_key, declared_content_type, declared_bytes, upload_expires_at,
                    processing_status, binding_status, cleanup_status, lock_version, created_at, updated_at)
                VALUES (UUID(), 'RESTAURANT', 'DIRECT_UPLOAD', 'ADMIN', 1, 'ADMIN', 1,
                    'test/photo-original', 'image/jpeg', 100, CURRENT_TIMESTAMP,
                    'PENDING_UPLOAD', 'UNBOUND', 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        insertRendition(jdbc, "RESTAURANT_HERO", 3);
        assertThatThrownBy(() -> insertRendition(jdbc, "PHOTO_DETAIL", 4))
                .isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class, cause -> {
                    assertThat(cause.getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains("ck_image_rendition_role");
                });

        var migration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("44").load().migrate();
        assertThat(migration.migrations).anySatisfy(applied -> assertThat(applied.version).isEqualTo("44"));
        assertThat(jdbc.queryForMap("SELECT * FROM media_pipeline_config WHERE id = 1"))
                .isEqualTo(previousConfig);
        insertRendition(jdbc, "PHOTO_DETAIL", 4);
        assertThat(jdbc.queryForList("SELECT role FROM image_rendition ORDER BY role", String.class))
                .containsExactly("PHOTO_DETAIL", "RESTAURANT_HERO");
        assertThatThrownBy(() -> insertRendition(jdbc, "UNKNOWN_ROLE", 4))
                .isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class, cause -> {
                    assertThat(cause.getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains("ck_image_rendition_role");
                });
    }

    private void insertRendition(JdbcTemplate jdbc, String role, int version) {
        jdbc.update("""
                INSERT INTO image_rendition (image_asset_id, role, spec_version, format, mime_type,
                    width, height, bytes, object_key, created_at, updated_at)
                VALUES (1, ?, ?, 'WEBP', 'image/webp', 432, 216, 100, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, role, version, "test/" + role);
    }
}
