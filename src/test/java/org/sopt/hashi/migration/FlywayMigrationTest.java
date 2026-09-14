package org.sopt.hashi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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

        assertThat(columnExists("target_processing_started_at")).isFalse();
        assertThat(columnExists("last_recovery_requested_at")).isFalse();
        assertThat(columnExists("processing_recovery_attempts")).isFalse();
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

    private boolean columnExists(String columnName) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'image_asset'
                       AND column_name = ?
                     """)) {
            statement.setString(1, columnName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
