ALTER TABLE restaurant_menu
    ADD COLUMN image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER image_key,
    ADD CONSTRAINT ck_restaurant_menu_asset_id CHECK (
        image_asset_id IS NULL
        OR image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD UNIQUE INDEX uq_restaurant_menu_asset_id (image_asset_id);
