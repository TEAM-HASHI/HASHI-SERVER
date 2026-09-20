ALTER TABLE image_asset
    ADD COLUMN purge_last_attempt_at DATETIME(6),
    ADD INDEX idx_image_asset_purge_retry (
        cleanup_status,
        purge_last_attempt_at,
        id
    );

-- Preserve an existing purge token and start time if a previous interrupted purge exists.
-- Later retries advance this separate timestamp, so repeatedly failing work does not
-- permanently occupy the oldest keyset batch.
UPDATE image_asset
SET purge_last_attempt_at = purge_started_at
WHERE cleanup_status IN ('PURGING', 'PURGED')
  AND purge_last_attempt_at IS NULL;
