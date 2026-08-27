package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private ImageAssetRepository imageAssetRepository;

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
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO media_pipeline_config (
                    id,
                    current_spec_version,
                    current_spec_digest,
                    issuance_enabled,
                    lock_version,
                    updated_at
                ) VALUES (2, 1, ?, FALSE, 0, CURRENT_TIMESTAMP(6))
                """, SPEC_DIGEST))
                .isInstanceOf(DataAccessException.class);
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

    @Test
    void source_version과_SHA256은_worker_wire_계약_길이로_저장한다() {
        Integer sourceVersionLength = columnLength("source_version_id");
        Integer checksumLength = columnLength("source_checksum_sha256");

        assertThat(sourceVersionLength).isEqualTo(1024);
        assertThat(checksumLength).isEqualTo(44);
    }

    @Test
    void source_SHA256은_44자_Base64만_허용한다() {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.REVIEW,
                MediaOwnerType.USER,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
        imageAssetRepository.saveAndFlush(asset);

        assertThatThrownBy(() -> updateVerifiedSource(assetId, "a".repeat(64)))
                .isInstanceOf(DataAccessException.class);
        assertThat(updateVerifiedSource(assetId, "A".repeat(43) + "=")).isEqualTo(1);
    }

    @Test
    void PROCESSING_recovery_상태와_keyset_index를_DB_제약으로_고정한다() {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.REVIEW,
                MediaOwnerType.USER,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
        imageAssetRepository.saveAndFlush(asset);
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE image_asset
                SET processing_status = 'PROCESSING',
                    source_version_id = 'version-1',
                    source_etag = '"etag-1"',
                    target_spec_version = 1,
                    target_spec_digest = ?,
                    target_processing_status = 'PROCESSING',
                    current_job_id = ?,
                    last_issued_spec_version = 1
                WHERE public_id = ?
                """, SPEC_DIGEST, jobId.toString(), assetId.toString()))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.update("""
                UPDATE image_asset
                SET processing_status = 'PROCESSING',
                    source_version_id = 'version-1',
                    source_etag = '"etag-1"',
                    target_spec_version = 1,
                    target_spec_digest = ?,
                    target_processing_status = 'PROCESSING',
                    target_processing_started_at = CURRENT_TIMESTAMP(6),
                    current_job_id = ?,
                    last_issued_spec_version = 1
                WHERE public_id = ?
                """, SPEC_DIGEST, jobId.toString(), assetId.toString())).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE image_asset
                SET processing_recovery_attempts = 1
                WHERE public_id = ?
                """, assetId.toString()))
                .isInstanceOf(DataAccessException.class);

        assertThat(indexColumns("idx_image_asset_processing_scan")).containsExactly(
                "cleanup_status",
                "target_processing_status",
                "target_processing_started_at",
                "id"
        );
        assertThat(indexColumns("idx_image_asset_cleanup_scan")).containsExactly(
                "cleanup_status",
                "binding_status",
                "processing_status",
                "creation_origin",
                "updated_at",
                "id"
        );
    }

    private int updateVerifiedSource(UUID assetId, String checksum) {
        return jdbcTemplate.update("""
                UPDATE image_asset
                SET actual_content_type = 'image/jpeg',
                    actual_bytes = 1024,
                    source_width = 100,
                    source_height = 100,
                    source_checksum_sha256 = ?
                WHERE public_id = ?
                """, checksum, assetId.toString());
    }

    private Integer columnLength(String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'image_asset'
                  AND column_name = ?
                """, Integer.class, columnName);
    }

    private List<String> indexColumns(String indexName) {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'image_asset'
                  AND index_name = ?
                ORDER BY seq_in_index
                """, String.class, indexName);
    }
}
