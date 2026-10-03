package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NoticeMediaMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_notice_migration")
            .withUsername("hashi")
            .withPassword("hashi");

    @Test
    void 빈_DB의_purpose_CHECK는_NOTICE와_기존_값을_허용하고_미등록값은_거절한다() throws SQLException {
        migrateFromEmpty(MigrationVersion.LATEST);

        for (MediaPurpose purpose : MediaPurpose.values()) {
            insertAsset(purpose.name());
        }

        assertThat(rows("SELECT purpose FROM image_asset ORDER BY id"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(MediaPurpose.values())
                        .map(purpose -> List.of(purpose.name())).toList());
        assertPurposeRejected("UNKNOWN_PURPOSE");
    }

    @Test
    void 빈_DB의_role_CHECK는_NOTICE_DETAIL과_기존_값을_허용하고_미등록값은_거절한다() throws SQLException {
        migrateFromEmpty(MigrationVersion.LATEST);
        long assetId = insertAsset(MediaPurpose.REVIEW.name());

        for (ImageRole role : ImageRole.values()) {
            insertRendition(assetId, role.name());
        }

        assertThat(rows("SELECT role FROM image_rendition ORDER BY id"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ImageRole.values())
                        .map(role -> List.of(role.name())).toList());
        assertRoleRejected(assetId, "UNKNOWN_ROLE");
    }

    @Test
    void V35에서_업그레이드하면_기존_데이터를_보존하고_공지_CHECK를_확장한다() throws SQLException {
        Flyway throughV35 = migrateFromEmpty(MigrationVersion.fromVersion("35"));
        for (MediaPurpose purpose : MediaPurpose.values()) {
            if (purpose != MediaPurpose.NOTICE) {
                insertAsset(purpose.name());
            }
        }
        long legacyAssetId = insertAsset(MediaPurpose.REVIEW.name());
        for (ImageRole role : ImageRole.values()) {
            if (role != ImageRole.NOTICE_DETAIL) {
                insertRendition(legacyAssetId, role.name());
            }
        }
        List<List<String>> existingAssets = rows("SELECT * FROM image_asset ORDER BY id");
        List<List<String>> existingRenditions = rows("SELECT * FROM image_rendition ORDER BY id");
        List<List<String>> existingHistory = rows("""
                SELECT version, checksum FROM flyway_schema_history
                WHERE success = TRUE ORDER BY installed_rank
                """);
        assertThat(throughV35.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("35"));
        assertPurposeRejected(MediaPurpose.NOTICE.name());
        assertRoleRejected(legacyAssetId, ImageRole.NOTICE_DETAIL.name());

        Flyway upgrade = flyway(MigrationVersion.fromVersion("35.1"));
        upgrade.migrate();

        assertThat(rows("SELECT * FROM image_asset ORDER BY id")).isEqualTo(existingAssets);
        assertThat(rows("SELECT * FROM image_rendition ORDER BY id")).isEqualTo(existingRenditions);
        assertThat(rows("""
                SELECT version, checksum FROM flyway_schema_history
                WHERE success = TRUE AND version <> '35.1' ORDER BY installed_rank
                """)).isEqualTo(existingHistory);
        long noticeAssetId = insertAsset(MediaPurpose.NOTICE.name());
        insertRendition(noticeAssetId, ImageRole.NOTICE_DETAIL.name());
        assertPurposeRejected("UNKNOWN_PURPOSE");
        assertRoleRejected(noticeAssetId, "UNKNOWN_ROLE");
        assertThat(upgrade.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("35.1"));
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(MigrationVersion.fromVersion("35_1"))
                .isEqualTo(MigrationVersion.fromVersion("35.1"))
                .isGreaterThan(MigrationVersion.fromVersion("35"))
                .isLessThan(MigrationVersion.fromVersion("36"));
    }

    private Flyway migrateFromEmpty(MigrationVersion target) {
        Flyway flyway = flyway(target);
        flyway.clean();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        return flyway;
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(false)
                .load();
    }

    private long insertAsset(String purpose) throws SQLException {
        UUID publicId = UUID.randomUUID();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO image_asset (
                         public_id, purpose, creation_origin, creator_actor_type, creator_subject_id,
                         owner_actor_type, owner_subject_id, original_object_key, declared_content_type,
                         declared_bytes, upload_expires_at, processing_status, binding_status,
                         cleanup_status, lock_version, created_at, updated_at
                     ) VALUES (
                         ?, ?, 'DIRECT_UPLOAD', 'ADMIN', 7, 'ADMIN', 7, ?, 'image/png',
                         1024, CURRENT_TIMESTAMP(6), 'PENDING_UPLOAD', 'UNBOUND', 'ACTIVE', 0,
                         CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                     )
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, publicId.toString());
            statement.setString(2, purpose);
            statement.setString(3, "media/originals/%s/original".formatted(publicId));
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void insertRendition(long assetId, String role) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO image_rendition (
                         image_asset_id, role, spec_version, format, mime_type,
                         width, height, bytes, object_key, created_at, updated_at
                     ) VALUES (?, ?, 3, 'WEBP', 'image/webp', 100, 50, 100, ?,
                               CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                     """)) {
            statement.setLong(1, assetId);
            statement.setString(2, role);
            statement.setString(3, "media/renditions/%s.webp".formatted(UUID.randomUUID()));
            statement.executeUpdate();
        }
    }

    private void assertPurposeRejected(String purpose) {
        assertThatThrownBy(() -> insertAsset(purpose))
                .isInstanceOfSatisfying(SQLException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(3819);
                    assertThat(exception.getMessage()).contains("ck_image_asset_purpose");
                });
    }

    private void assertRoleRejected(long assetId, String role) {
        assertThatThrownBy(() -> insertRendition(assetId, role))
                .isInstanceOfSatisfying(SQLException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(3819);
                    assertThat(exception.getMessage()).contains("ck_image_rendition_role");
                });
    }

    private List<List<String>> rows(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
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
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
