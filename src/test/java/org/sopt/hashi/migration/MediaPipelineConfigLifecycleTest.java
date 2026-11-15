package org.sopt.hashi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.HashiApplication;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.service.MediaAssetTransactionService;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 모듈 업무 로직이 아니라 Flyway와 실제 애플리케이션 시작 과정을 검증한다.
 * 같은 MySQL에 SpringApplication을 다시 실행해 시작 코드가 운영 설정을 덮어쓰지 않는지 확인한다.
 */
@Testcontainers
class MediaPipelineConfigLifecycleTest {

    @Container
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Test
    void 변경한_설정은_migration_재실행과_애플리케이션_재시작_후에도_유지된다() {
        Map<String, Object> expectedConfig;
        long migrationCount;

        try (ConfigurableApplicationContext initial = startApplication()) {
            JdbcTemplate jdbcTemplate = initial.getBean(JdbcTemplate.class);
            assertThat(readConfig(jdbcTemplate).get("issuance_enabled")).isEqualTo(false);

            int updatedRows = jdbcTemplate.update("""
                    UPDATE media_pipeline_config
                    SET issuance_enabled = TRUE, lock_version = 7, updated_at = ?
                    WHERE id = 1
                    """, LocalDateTime.of(2026, 1, 2, 3, 4, 5));
            assertThat(updatedRows).isEqualTo(1);
            expectedConfig = readConfig(jdbcTemplate);
            assertThat(expectedConfig.get("issuance_enabled")).isEqualTo(true);
            migrationCount = countAppliedMigrations(jdbcTemplate);

            MigrateResult replay = initial.getBean(Flyway.class).migrate();

            assertThat(replay.success).isTrue();
            assertThat(replay.migrationsExecuted).isZero();
            assertThat(countAppliedMigrations(jdbcTemplate)).isEqualTo(migrationCount);
            assertThat(readConfig(jdbcTemplate)).isEqualTo(expectedConfig);
        }

        try (ConfigurableApplicationContext restarted = startApplication()) {
            JdbcTemplate jdbcTemplate = restarted.getBean(JdbcTemplate.class);

            assertThat(countAppliedMigrations(jdbcTemplate)).isEqualTo(migrationCount);
            assertThat(readConfig(jdbcTemplate)).isEqualTo(expectedConfig);
            restarted.getBean(MediaAssetTransactionService.class).assertIssuanceAvailable();
        }
    }

    @Test
    void 설정이_없으면_재시작해도_재생성하지_않고_새_작업_발급을_차단한다() {
        try (ConfigurableApplicationContext initial = startApplication()) {
            JdbcTemplate jdbcTemplate = initial.getBean(JdbcTemplate.class);

            assertThat(jdbcTemplate.update("DELETE FROM media_pipeline_config WHERE id = 1"))
                    .isEqualTo(1);
        }

        try (ConfigurableApplicationContext restarted = startApplication()) {
            JdbcTemplate jdbcTemplate = restarted.getBean(JdbcTemplate.class);
            MediaAssetTransactionService service = restarted.getBean(MediaAssetTransactionService.class);

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_pipeline_config", Long.class))
                    .isZero();
            assertThatThrownBy(service::assertIssuanceAvailable)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
    }

    private Map<String, Object> readConfig(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForMap("""
                SELECT id, current_spec_version, current_spec_digest,
                       issuance_enabled, lock_version, updated_at
                FROM media_pipeline_config
                WHERE id = 1
                """);
    }

    private long countAppliedMigrations(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE
                """, Long.class);
    }

    private ConfigurableApplicationContext startApplication() {
        SpringApplication application = new SpringApplication(HashiApplication.class);
        application.setRegisterShutdownHook(false);

        // 로컬/운영 설정을 읽지 않고 datasource와 Flyway 모두 이 테스트의 컨테이너만 사용한다.
        return application.run(
                "--spring.config.location=classpath:/application.yml",
                "--spring.profiles.active=media-config-test",
                "--spring.datasource.url=" + mysql.getJdbcUrl(),
                "--spring.datasource.username=" + mysql.getUsername(),
                "--spring.datasource.password=" + mysql.getPassword(),
                "--spring.flyway.url=" + mysql.getJdbcUrl(),
                "--spring.flyway.user=" + mysql.getUsername(),
                "--spring.flyway.password=" + mysql.getPassword(),
                "--spring.flyway.enabled=true",
                "--spring.flyway.clean-disabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.data.redis.url=redis://127.0.0.1:6379",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
                "--kakao.client-id=test-client-id",
                "--kakao.redirect-uri=https://app.hashi.test/callback",
                "--hashi.storage.region=ap-northeast-2",
                "--hashi.storage.bucket=hashi-test-uploads",
                "--hashi.storage.cloudfront-domain=https://cdn.hashi.test",
                "--hashi.media.original-storage.region=ap-northeast-2",
                "--hashi.media.original-storage.bucket=hashi-test-originals"
        );
    }
}
