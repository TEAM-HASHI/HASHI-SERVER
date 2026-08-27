ALTER TABLE image_asset
    DROP CHECK ck_image_asset_verified_source;

ALTER TABLE media_pipeline_config
    MODIFY COLUMN id INT NOT NULL;

ALTER TABLE image_asset
    MODIFY COLUMN source_version_id VARCHAR(1024),
    MODIFY COLUMN source_checksum_sha256 CHAR(44) CHARACTER SET ascii COLLATE ascii_bin;

ALTER TABLE image_asset
    ADD CONSTRAINT ck_image_asset_verified_source CHECK (
        (
            actual_content_type IS NULL
            AND actual_bytes IS NULL
            AND source_width IS NULL
            AND source_height IS NULL
            AND source_checksum_sha256 IS NULL
        )
        OR (
            actual_content_type IS NOT NULL
            AND actual_bytes > 0
            AND source_width > 0
            AND source_height > 0
            AND source_checksum_sha256 REGEXP '^[A-Za-z0-9+/]{43}=$'
        )
    );
