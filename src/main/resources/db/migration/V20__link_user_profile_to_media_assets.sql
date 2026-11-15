UPDATE users
SET profile_image_key = NULL
WHERE profile_image_key IS NOT NULL
  AND CHAR_LENGTH(TRIM(profile_image_key)) = 0;

ALTER TABLE users
    ADD COLUMN profile_image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER profile_image_key,
    ADD CONSTRAINT ck_users_profile_image_key CHECK (
        profile_image_key IS NULL OR CHAR_LENGTH(TRIM(profile_image_key)) > 0
    ),
    ADD CONSTRAINT ck_users_profile_image_asset_id CHECK (
        profile_image_asset_id IS NULL
        OR profile_image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD UNIQUE INDEX uq_users_profile_image_asset_id (profile_image_asset_id);
