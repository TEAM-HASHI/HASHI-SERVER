-- Deployment precondition: issuance must stay disabled and no target PROCESSING row may exist.
-- Keeping the recovery columns, constraint, and indexes in one ALTER makes a failed precondition
-- roll back the whole schema change instead of leaving a partially applied Flyway migration.
ALTER TABLE image_asset
    ADD COLUMN target_processing_started_at DATETIME(6),
    ADD COLUMN last_recovery_requested_at DATETIME(6),
    ADD COLUMN processing_recovery_attempts INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_image_asset_processing_recovery CHECK (
        (
            target_processing_status IS NOT NULL
            AND target_processing_status = 'PROCESSING'
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
    ),
    DROP INDEX idx_image_asset_processing_scan,
    ADD INDEX idx_image_asset_processing_scan (
        cleanup_status,
        target_processing_status,
        target_processing_started_at,
        id
    ),
    DROP INDEX idx_image_asset_cleanup_scan,
    ADD INDEX idx_image_asset_cleanup_scan (
        cleanup_status,
        binding_status,
        creation_origin,
        target_processing_status,
        updated_at,
        id,
        processing_status
    ),
    ADD INDEX idx_image_asset_status_cleanup_scan (
        cleanup_status,
        processing_status,
        updated_at,
        id
    );
