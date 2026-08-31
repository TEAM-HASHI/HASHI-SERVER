package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MediaBackfillIdentityUpgradeTest {

    private static final String HASH = "a".repeat(64);

    @Container
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_upgrade")
            .withUsername("hashi")
            .withPassword("hashi");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void V19를_준비한다() {
        flyway("19").migrate();
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    }

    @Test
    void 기존_asset을_보존하고_backfill_NULL만_차단하면서_V20으로_이행한다() {
        UUID directId = insertAsset(false, null);
        UUID backfillId = insertAsset(true, HASH);
        var before = jdbcTemplate.queryForList("SELECT * FROM image_asset ORDER BY id");

        Flyway v20 = flyway("20");
        assertThat(v20.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbcTemplate.queryForList("SELECT * FROM image_asset ORDER BY id")).isEqualTo(before);
        assertThat(v20.validateWithResult().validationSuccessful).isTrue();
        assertCheckViolation(() -> updateHash(backfillId, null), "ck_image_asset_backfill_identity_required");
        assertCheckViolation(() -> insertAsset(true, null), "ck_image_asset_backfill_identity_required");
        assertCheckViolation(() -> updateHash(backfillId, "bad-hash"), "ck_image_asset_backfill_identity");
        assertThatThrownBy(() -> insertAsset(true, HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCheckViolation(() -> updateHash(directId, "b".repeat(64)), "ck_image_asset_backfill_identity");
        insertAsset(false, null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM image_asset
                WHERE creation_origin='DIRECT_UPLOAD' AND backfill_identity_hash IS NULL
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void V19의_backfill_hash가_NULL이면_데이터를_바꾸지_않고_이행을_중단한다() {
        insertAsset(false, null);
        insertAsset(true, null);
        var before = jdbcTemplate.queryForList("SELECT * FROM image_asset ORDER BY id");

        assertThatThrownBy(() -> flyway("20").migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("ck_image_asset_backfill_identity_required");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM image_asset ORDER BY id")).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version='20' AND success=TRUE
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE()
                  AND constraint_name='ck_image_asset_backfill_identity_required'
                """, Integer.class)).isZero();
    }

    private Flyway flyway(String version) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    private UUID insertAsset(boolean backfill, String hash) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO image_asset (
                    public_id, purpose, creation_origin, creator_actor_type, creator_subject_id,
                    owner_actor_type, owner_subject_id, original_object_key, declared_content_type,
                    declared_bytes, upload_expires_at, processing_status, binding_status,
                    backfill_identity_hash, cleanup_status, lock_version, created_at, updated_at
                ) VALUES (?, 'PROFILE', ?, ?, ?, ?, ?, ?, 'image/jpeg', 1024,
                    CURRENT_TIMESTAMP(6) + INTERVAL 1 DAY, 'PENDING_UPLOAD', 'UNBOUND', ?,
                    'ACTIVE', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, id.toString(), backfill ? "SYSTEM_BACKFILL" : "DIRECT_UPLOAD",
                backfill ? null : "USER", backfill ? null : 1L,
                backfill ? "SYSTEM_BACKFILL" : "USER", backfill ? null : 1L,
                "media/originals/" + id + "/original", hash);
        return id;
    }

    private void updateHash(UUID id, String hash) {
        jdbcTemplate.update("UPDATE image_asset SET backfill_identity_hash=? WHERE public_id=?",
                hash, id.toString());
    }

    private void assertCheckViolation(Runnable action, String constraint) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(constraint)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
    }
}
