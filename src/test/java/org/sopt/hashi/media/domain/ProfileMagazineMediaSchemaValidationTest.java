package org.sopt.hashi.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileMagazineMediaSchemaValidationTest {

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void asset_ID_컬럼은_모듈_FK없이_ASCII_UUID와_local_unique를_사용한다() {
        assertAssetColumn("users", "profile_image_asset_id",
                "uq_users_profile_image_asset_id");
        assertAssetColumn("magazine", "banner_image_asset_id",
                "uq_magazine_banner_image_asset_id");
        assertAssetColumn("magazine", "thumbnail_image_asset_id",
                "uq_magazine_thumbnail_image_asset_id");

        Integer mediaForeignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.key_column_usage
                WHERE constraint_schema = DATABASE()
                  AND table_name IN ('users', 'magazine')
                  AND referenced_table_name = 'image_asset'
                """, Integer.class);
        assertThat(mediaForeignKeys).isZero();
    }

    @Test
    void 프로필은_이미지_없음을_허용하고_asset_ID는_single_use다() {
        insertUser("프로필없음", "none@hashi.test", "01000000001", null, null);
        UUID assetId = UUID.randomUUID();
        insertUser("asset회원", "asset@hashi.test", "01000000002", null, assetId);

        assertThatThrownBy(() -> insertUser(
                "asset중복", "duplicate@hashi.test", "01000000003", null, assetId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUser(
                "공백키", "blank@hashi.test", "01000000004", " ", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUser(
                "잘못된uuid", "invalid@hashi.test", "01000000005", null, "INVALID"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 매거진은_각_슬롯마다_legacy_key나_asset_ID가_필수다() {
        UUID bannerAssetId = UUID.randomUUID();
        UUID thumbnailAssetId = UUID.randomUUID();
        insertMagazine(null, bannerAssetId, null, thumbnailAssetId);
        insertMagazine("magazines/banner.jpg", null,
                "magazines/thumbnail.jpg", null);

        assertThatThrownBy(() -> insertMagazine(
                null, null, "magazines/thumbnail-2.jpg", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMagazine(
                "magazines/banner-2.jpg", null, null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMagazine(
                " ", null, "magazines/thumbnail-3.jpg", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMagazine(
                null, bannerAssetId, null, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    private void assertAssetColumn(String tableName, String columnName, String indexName) {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                SELECT character_maximum_length,
                       character_set_name,
                       collation_name,
                       is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, tableName, columnName);
        Integer uniqueIndexColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                  AND non_unique = 0
                  AND column_name = ?
                """, Integer.class, tableName, indexName, columnName);

        assertThat(column.get("character_maximum_length")).isEqualTo(36L);
        assertThat(column.get("character_set_name")).isEqualTo("ascii");
        assertThat(column.get("collation_name")).isEqualTo("ascii_bin");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(uniqueIndexColumns).isEqualTo(1);
    }

    private void insertUser(
            String nickname,
            String email,
            String phone,
            String profileImageKey,
            Object profileImageAssetId
    ) {
        jdbcTemplate.update("""
                INSERT INTO users (
                    nickname, name_eng, birth_date, phone, email,
                    profile_image_key, profile_image_asset_id, deleted,
                    created_at, updated_at
                ) VALUES (?, 'HASHI USER', '1998-01-01', ?, ?, ?, ?, FALSE,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, nickname, phone, email, profileImageKey,
                profileImageAssetId == null ? null : profileImageAssetId.toString());
    }

    private void insertMagazine(
            String bannerKey,
            UUID bannerAssetId,
            String thumbnailKey,
            UUID thumbnailAssetId
    ) {
        jdbcTemplate.update("""
                INSERT INTO magazine (
                    title, banner_key, banner_image_asset_id,
                    thumbnail_key, thumbnail_image_asset_id,
                    instagram_redirect_url, deleted, created_at, updated_at
                ) VALUES ('스키마 검증 매거진', ?, ?, ?, ?,
                          'https://www.instagram.com/p/schema/', FALSE,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                bannerKey, bannerAssetId == null ? null : bannerAssetId.toString(),
                thumbnailKey, thumbnailAssetId == null ? null : thumbnailAssetId.toString());
    }
}
