package org.sopt.hashi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import org.flywaydb.core.Flyway;
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
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();

        flyway.migrate();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void V15의_hex_checksum을_Base64로_보존해서_변환한다() throws Exception {
        try (MySQLContainer<?> upgradeMysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("hashi_upgrade")
                .withUsername("hashi")
                .withPassword("hashi")) {
            upgradeMysql.start();
            String jdbcUrl = upgradeMysql.getJdbcUrl();
            Flyway v15 = flyway(
                    jdbcUrl,
                    upgradeMysql.getUsername(),
                    upgradeMysql.getPassword(),
                    MigrationVersion.fromVersion("15")
            );
            v15.migrate();

            String hexChecksum = "a".repeat(64);
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl, upgradeMysql.getUsername(), upgradeMysql.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO image_asset (
                        public_id, purpose, creation_origin,
                        creator_actor_type, creator_subject_id,
                        owner_actor_type, owner_subject_id,
                        original_object_key, declared_content_type, declared_bytes,
                        upload_expires_at, source_version_id, source_etag,
                        actual_content_type, actual_bytes, source_width, source_height,
                        source_checksum_sha256, processing_status, binding_status,
                        active_spec_version, active_spec_digest, last_issued_spec_version,
                        cleanup_status, lock_version, created_at, updated_at
                    ) VALUES (
                        'a3af06f1-4ef2-46f8-a489-2347fb840447', 'REVIEW', 'DIRECT_UPLOAD',
                        'USER', 1, 'USER', 1,
                        'media/originals/a3af06f1-4ef2-46f8-a489-2347fb840447/original',
                        'image/jpeg', 1024, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY),
                        'version-1', '"etag-1"', 'image/jpeg', 1024, 100, 100,
                        '%s', 'READY', 'UNBOUND', 1,
                        '91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32',
                        1, 'ACTIVE', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                    )
                    """.formatted(hexChecksum));
            }

            Flyway latest = flyway(
                    jdbcUrl,
                    upgradeMysql.getUsername(),
                    upgradeMysql.getPassword(),
                    null
            );
            latest.migrate();

            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl, upgradeMysql.getUsername(), upgradeMysql.getPassword());
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                     SELECT source_checksum_sha256
                     FROM image_asset
                     WHERE public_id = 'a3af06f1-4ef2-46f8-a489-2347fb840447'
                     """)) {
                assertThat(result.next()).isTrue();
                String expected = Base64.getEncoder().encodeToString(
                        HexFormat.of().parseHex(hexChecksum));
                assertThat(result.getString(1)).isEqualTo(expected);
            }
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        }
    }

    private Flyway flyway(String jdbcUrl, String username, String password,
                          MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
