CREATE TEMPORARY TABLE v18_restaurant_media_guard (
    violation VARCHAR(100) NOT NULL,
    valid TINYINT NOT NULL,
    CONSTRAINT ck_v18_restaurant_media_guard CHECK (valid = 1)
);

INSERT INTO v18_restaurant_media_guard (violation, valid)
SELECT 'restaurant_image.file_key is blank', 0
WHERE EXISTS (
    SELECT 1
    FROM restaurant_image
    WHERE CHAR_LENGTH(TRIM(file_key)) = 0
);

DROP TEMPORARY TABLE v18_restaurant_media_guard;

ALTER TABLE restaurant_image
    MODIFY COLUMN file_key VARCHAR(500) NULL,
    ADD COLUMN image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER file_key,
    ADD CONSTRAINT ck_restaurant_image_source CHECK (
        (file_key IS NULL OR CHAR_LENGTH(TRIM(file_key)) > 0)
        AND (file_key IS NOT NULL OR image_asset_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_restaurant_image_asset_id CHECK (
        image_asset_id IS NULL
        OR image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD UNIQUE INDEX uq_restaurant_image_asset_id (image_asset_id);
