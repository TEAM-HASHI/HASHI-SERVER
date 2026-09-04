package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MediaCleanupMigrationTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 8, 20, 12, 0);
    private static final String INSERT_ASSET = """
            INSERT INTO image_asset (
                public_id, purpose, creation_origin, creator_actor_type, creator_subject_id,
                owner_actor_type, owner_subject_id, original_object_key, declared_content_type,
                declared_bytes, upload_expires_at, processing_status, binding_status,
                cleanup_status, purge_token, purge_started_at, objects_purged_at,
                lock_version, created_at, updated_at
            ) VALUES (?, 'REVIEW', 'DIRECT_UPLOAD', 'USER', 1, 'USER', 1, ?, 'image/jpeg',
                      1024, ?, 'EXPIRED', 'UNBOUND', ?, ?, ?, ?, 0, ?, ?)
            """;
    private static final String RESUME_QUERY = """
            SELECT id, purge_last_attempt_at
            FROM image_asset
            WHERE cleanup_status = 'PURGING'
              AND purge_last_attempt_at <= ?
              AND (purge_last_attempt_at > ?
                   OR (purge_last_attempt_at = ? AND id > ?))
            ORDER BY purge_last_attempt_at, id
            LIMIT 5
            """;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_cleanup_migration")
            .withUsername("hashi")
            .withPassword("hashi");

    private JdbcTemplate jdbc;

    @BeforeEach
    void V24의_격리된_테스트_스키마를_준비한다() {
        Flyway baseline = flyway("24");
        baseline.clean();
        baseline.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void 기존_정리_상태와_token은_보존하고_재시도_시각만_최초_시각으로_채운다() {
        jdbc.update(INSERT_ASSET, fixture(MediaCleanupStatus.ACTIVE));
        jdbc.update(INSERT_ASSET, fixture(MediaCleanupStatus.PURGING));
        jdbc.update(INSERT_ASSET, fixture(MediaCleanupStatus.PURGED));
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM image_asset ORDER BY id");
        jdbc.update("UPDATE media_pipeline_config SET issuance_enabled=TRUE WHERE id=1");

        Flyway migration = flyway("25");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);

        List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM image_asset ORDER BY id");
        assertThat(after).hasSameSizeAs(before);
        for (int index = 0; index < before.size(); index++) {
            Map<String, Object> oldRow = before.get(index);
            Map<String, Object> newRow = after.get(index);
            assertThat(newRow).containsAllEntriesOf(oldRow);
            assertThat(newRow.get("purge_last_attempt_at")).isEqualTo(oldRow.get("purge_started_at"));
        }
        assertThat(jdbc.queryForObject(
                "SELECT issuance_enabled FROM media_pipeline_config WHERE id=1", Boolean.class)).isTrue();
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void migration_재실행은_갱신된_재시도_시각과_기존_설정을_초기화하지_않는다() {
        jdbc.update(INSERT_ASSET, fixture(MediaCleanupStatus.PURGING));
        Flyway migration = flyway("25");
        migration.migrate();
        LocalDateTime retriedAt = STARTED.plusDays(2);
        jdbc.update("UPDATE image_asset SET purge_last_attempt_at=?", retriedAt);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM image_asset");

        assertThat(migration.migrate().migrationsExecuted).isZero();

        assertThat(jdbc.queryForMap("SELECT * FROM image_asset")).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT purge_last_attempt_at FROM image_asset", Timestamp.class).toLocalDateTime())
                .isEqualTo(retriedAt);
    }

    @Test
    void 중단된_정리_조회는_인덱스와_시각_ID_커서로_페이지를_이동한다() {
        List<Object[]> fixtures = new ArrayList<>();
        for (int index = 0; index < 1200; index++) {
            MediaCleanupStatus status = index < 60 ? MediaCleanupStatus.PURGING : MediaCleanupStatus.ACTIVE;
            fixtures.add(fixture(status));
        }
        jdbc.batchUpdate(INSERT_ASSET, fixtures);
        flyway("25").migrate();
        jdbc.execute("ANALYZE TABLE image_asset");
        LocalDateTime lowerBound = STARTED.minusDays(1);
        LocalDateTime upperBound = STARTED.plusDays(1);
        Object[] firstParameters = {upperBound, lowerBound, lowerBound, 0L};

        Map<String, Object> plan = jdbc.queryForMap("EXPLAIN " + RESUME_QUERY, firstParameters);
        List<Map<String, Object>> first = jdbc.queryForList(RESUME_QUERY, firstParameters);
        Map<String, Object> last = first.getLast();
        List<Map<String, Object>> second = jdbc.queryForList(RESUME_QUERY,
                upperBound, last.get("purge_last_attempt_at"), last.get("purge_last_attempt_at"), last.get("id"));

        assertThat(plan.get("key")).isEqualTo("idx_image_asset_purge_retry");
        assertThat(plan.get("type")).isIn("range", "ref");
        assertThat(first).hasSize(5);
        assertThat(second).hasSize(5);
        assertThat(second).extracting(row -> row.get("id"))
                .doesNotContainAnyElementsOf(first.stream().map(row -> row.get("id")).toList());
        assertThat(((Number) second.getFirst().get("id")).longValue())
                .isGreaterThan(((Number) last.get("id")).longValue());
    }

    private Object[] fixture(MediaCleanupStatus status) {
        UUID publicId = UUID.randomUUID();
        boolean started = status != MediaCleanupStatus.ACTIVE;
        return new Object[] {
                publicId.toString(), "media/originals/%s/original".formatted(publicId),
                STARTED.minusDays(10), status.name(),
                started ? UUID.randomUUID().toString() : null,
                started ? STARTED : null,
                status == MediaCleanupStatus.PURGED ? STARTED.plusHours(1) : null,
                STARTED.minusDays(11), STARTED
        };
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(false)
                .load();
    }
}
