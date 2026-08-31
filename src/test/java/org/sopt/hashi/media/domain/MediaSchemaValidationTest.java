package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaSchemaValidationTest {

    private static final String SPEC_DIGEST =
            "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void Flyway_스키마와_JPA_매핑이_일치한다() {
    }

    @Test
    void pipeline은_v1_digest와_issuance_false로_시작한다() {
        Map<String, Object> config = jdbcTemplate.queryForMap("""
                SELECT current_spec_version, current_spec_digest, issuance_enabled
                FROM media_pipeline_config
                WHERE id = 1
                """);

        assertThat(config.get("current_spec_version")).isEqualTo(1);
        assertThat(config.get("current_spec_digest")).isEqualTo(SPEC_DIGEST);
        assertThat(config.get("issuance_enabled")).isEqualTo(false);
    }

    @Test
    void pipeline_config는_id_1인_singleton만_허용한다() {
        DataAccessException exception = assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO media_pipeline_config (
                    id,
                    current_spec_version,
                    current_spec_digest,
                    issuance_enabled,
                    lock_version,
                    updated_at
                ) VALUES (2, 1, ?, FALSE, 0, CURRENT_TIMESTAMP(6))
                """, SPEC_DIGEST));

        assertThat(exception.getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sqlException -> {
                    assertThat(sqlException.getErrorCode()).isEqualTo(3819);
                    assertThat(sqlException.getMessage()).contains("ck_media_pipeline_config_singleton");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_pipeline_config", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_pipeline_config WHERE id = 2", Long.class))
                .isZero();
    }

    @Test
    void Event_Publication_Registry_컬럼과_완료일_index를_보정한다() {
        Integer serializedEventLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'event_publication'
                  AND column_name = 'serialized_event'
                """, Integer.class);
        Integer listenerIdLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'event_publication'
                  AND column_name = 'listener_id'
                """, Integer.class);
        Integer completionDateIndexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'event_publication'
                  AND index_name = 'idx_event_publication_completion_date'
                """, Integer.class);

        assertThat(serializedEventLength).isEqualTo(4000);
        assertThat(listenerIdLength).isEqualTo(512);
        assertThat(completionDateIndexCount).isEqualTo(1);
    }
}
