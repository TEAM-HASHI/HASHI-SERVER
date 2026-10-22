-- Stable association IDs require a nullable staging order while swapping slots.
-- The review write lock and one transaction restore every order to 0..9 before commit.
ALTER TABLE review_image
    MODIFY COLUMN file_key VARCHAR(500) NULL,
    MODIFY COLUMN display_order INT NULL,
    ADD COLUMN image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER file_key,
    ADD CONSTRAINT ck_review_image_source CHECK (
        (file_key IS NULL OR CHAR_LENGTH(TRIM(file_key)) > 0)
        AND (file_key IS NOT NULL OR image_asset_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_review_image_asset_id CHECK (
        image_asset_id IS NULL
        OR image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD UNIQUE INDEX uq_review_image_asset_id (image_asset_id);
