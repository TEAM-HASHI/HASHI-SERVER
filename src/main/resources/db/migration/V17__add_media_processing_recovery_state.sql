ALTER TABLE image_asset
    ADD COLUMN target_processing_started_at DATETIME(6),
    ADD COLUMN last_recovery_requested_at DATETIME(6),
    ADD COLUMN processing_recovery_attempts INT NOT NULL DEFAULT 0;

UPDATE image_asset
SET target_processing_started_at = updated_at
WHERE target_processing_status = 'PROCESSING';

ALTER TABLE image_asset
    ADD CONSTRAINT ck_image_asset_processing_recovery CHECK (
        (
            target_processing_status = 'PROCESSING'
            AND target_processing_started_at IS NOT NULL
            AND processing_recovery_attempts >= 0
            AND (
                (
                    processing_recovery_attempts = 0
                    AND last_recovery_requested_at IS NULL
                )
                OR (
                    processing_recovery_attempts > 0
                    AND last_recovery_requested_at IS NOT NULL
                )
            )
        )
        OR (
            target_processing_status IS NULL
            AND target_processing_started_at IS NULL
            AND last_recovery_requested_at IS NULL
            AND processing_recovery_attempts = 0
        )
    );

DROP INDEX idx_image_asset_processing_scan ON image_asset;

CREATE INDEX idx_image_asset_processing_scan ON image_asset (
    cleanup_status,
    target_processing_status,
    target_processing_started_at,
    id
);

DROP INDEX idx_image_asset_cleanup_scan ON image_asset;

CREATE INDEX idx_image_asset_cleanup_scan ON image_asset (
    cleanup_status,
    binding_status,
    processing_status,
    creation_origin,
    updated_at,
    id
);
