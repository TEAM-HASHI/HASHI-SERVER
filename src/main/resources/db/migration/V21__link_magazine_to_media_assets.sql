CREATE TEMPORARY TABLE v21_magazine_media_guard (
    violation VARCHAR(100) NOT NULL,
    valid TINYINT NOT NULL,
    CONSTRAINT ck_v21_magazine_media_guard CHECK (valid = 1)
);

INSERT INTO v21_magazine_media_guard (violation, valid)
SELECT 'magazine.banner_key is blank', 0
WHERE EXISTS (
    SELECT 1
    FROM magazine
    WHERE CHAR_LENGTH(TRIM(banner_key)) = 0
);

INSERT INTO v21_magazine_media_guard (violation, valid)
SELECT 'magazine.thumbnail_key is blank', 0
WHERE EXISTS (
    SELECT 1
    FROM magazine
    WHERE CHAR_LENGTH(TRIM(thumbnail_key)) = 0
);

DROP TEMPORARY TABLE v21_magazine_media_guard;

ALTER TABLE magazine
    MODIFY COLUMN banner_key VARCHAR(500) NULL,
    MODIFY COLUMN thumbnail_key VARCHAR(500) NULL,
    ADD COLUMN banner_image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER banner_key,
    ADD COLUMN thumbnail_image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER thumbnail_key,
    ADD CONSTRAINT ck_magazine_banner_source CHECK (
        (banner_key IS NULL OR CHAR_LENGTH(TRIM(banner_key)) > 0)
        AND (banner_key IS NOT NULL OR banner_image_asset_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_magazine_thumbnail_source CHECK (
        (thumbnail_key IS NULL OR CHAR_LENGTH(TRIM(thumbnail_key)) > 0)
        AND (thumbnail_key IS NOT NULL OR thumbnail_image_asset_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_magazine_banner_asset_id CHECK (
        banner_image_asset_id IS NULL
        OR banner_image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD CONSTRAINT ck_magazine_thumbnail_asset_id CHECK (
        thumbnail_image_asset_id IS NULL
        OR thumbnail_image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD UNIQUE INDEX uq_magazine_banner_image_asset_id (banner_image_asset_id),
    ADD UNIQUE INDEX uq_magazine_thumbnail_image_asset_id (thumbnail_image_asset_id);
