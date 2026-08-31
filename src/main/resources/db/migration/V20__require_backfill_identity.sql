ALTER TABLE image_asset
    ADD CONSTRAINT ck_image_asset_backfill_identity_required CHECK (
        creation_origin <> 'SYSTEM_BACKFILL'
        OR backfill_identity_hash IS NOT NULL
    );
