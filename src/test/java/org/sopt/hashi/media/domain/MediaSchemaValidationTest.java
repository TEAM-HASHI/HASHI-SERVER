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
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";

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
    void verified_source는_일부_컬럼만_채울_수_없다() {
        assertDirectUploadConstraintViolation(
                "actual_content_type",
                "'image/jpeg'",
                "ck_image_asset_verified_source"
        );
    }

    @Test
    void active_spec은_version과_digest를_함께_저장해야_한다() {
        assertDirectUploadConstraintViolation(
                "active_spec_version",
                "1",
                "ck_image_asset_active_spec"
        );
    }

    @Test
    void target_spec은_처리_tuple을_모두_저장해야_한다() {
        assertDirectUploadConstraintViolation(
                "target_spec_version",
                "1",
                "ck_image_asset_target_spec"
        );
    }

    @Test
    void last_failure는_version과_code를_함께_저장해야_한다() {
        assertDirectUploadConstraintViolation(
                "last_failure_code",
                "'INVALID_IMAGE_DATA'",
                "ck_image_asset_last_failure"
        );
    }

    @Test
    void SYSTEM_BACKFILL은_identity_hash가_필수다() {
        String publicId = java.util.UUID.randomUUID().toString();
        String objectKey = "media/originals/%s/original".formatted(publicId);

        DataAccessException exception = assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO image_asset (
                    public_id,
                    purpose,
                    creation_origin,
                    owner_actor_type,
                    original_object_key,
                    declared_content_type,
                    declared_bytes,
                    upload_expires_at,
                    processing_status,
                    binding_status,
                    cleanup_status,
                    lock_version,
                    created_at,
                    updated_at
                ) VALUES (?, 'REVIEW', 'SYSTEM_BACKFILL', 'SYSTEM_BACKFILL', ?, 'image/jpeg', 1024,
                          CURRENT_TIMESTAMP(6), 'PENDING_UPLOAD', 'UNBOUND', 'ACTIVE', 0,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, publicId, objectKey));

        assertConstraintViolation(exception, "ck_image_asset_backfill_identity");
        assertThat(imageAssetCount(publicId)).isZero();
    }

    @Test
    void source_version_ID_컬럼은_1024_길이로_정의한다() {
        Integer sourceVersionIdLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'image_asset'
                  AND column_name = 'source_version_id'
                """, Integer.class);

        assertThat(sourceVersionIdLength).isEqualTo(1024);
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

    private void assertDirectUploadConstraintViolation(
            String optionalColumn,
            String optionalValue,
            String constraintName
    ) {
        String publicId = java.util.UUID.randomUUID().toString();
        String objectKey = "media/originals/%s/original".formatted(publicId);
        String sql = """
                INSERT INTO image_asset (
                    public_id,
                    purpose,
                    creation_origin,
                    creator_actor_type,
                    creator_subject_id,
                    owner_actor_type,
                    owner_subject_id,
                    original_object_key,
                    declared_content_type,
                    declared_bytes,
                    upload_expires_at,
                    processing_status,
                    binding_status,
                    cleanup_status,
                    lock_version,
                    created_at,
                    updated_at,
                    %s
                ) VALUES (?, 'REVIEW', 'DIRECT_UPLOAD', 'USER', 1, 'USER', 1, ?, 'image/jpeg', 1024,
                          CURRENT_TIMESTAMP(6), 'PENDING_UPLOAD', 'UNBOUND', 'ACTIVE', 0,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), %s)
                """.formatted(optionalColumn, optionalValue);

        DataAccessException exception = assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(sql, publicId, objectKey)
        );

        assertConstraintViolation(exception, constraintName);
        assertThat(imageAssetCount(publicId)).isZero();
    }

    private void assertConstraintViolation(DataAccessException exception, String constraintName) {
        assertThat(exception.getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sqlException -> {
                    assertThat(sqlException.getErrorCode()).isEqualTo(3819);
                    assertThat(sqlException.getMessage()).contains(constraintName);
                });
    }

    private Long imageAssetCount(String publicId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM image_asset WHERE public_id = ?",
                Long.class,
                publicId
        );
    }
}
