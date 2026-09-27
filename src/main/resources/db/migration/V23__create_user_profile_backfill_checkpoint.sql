CREATE TABLE user_profile_backfill_checkpoint (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    upper_bound_id BIGINT NOT NULL,
    cursor_id BIGINT NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    scanned_count BIGINT NOT NULL DEFAULT 0,
    prepared_count BIGINT NOT NULL DEFAULT 0,
    attached_count BIGINT NOT NULL DEFAULT 0,
    skipped_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id),
    CONSTRAINT ck_user_profile_backfill_run_id CHECK (
        run_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_user_profile_backfill_mode CHECK (mode IN ('PREPARE', 'ATTACH')),
    CONSTRAINT ck_user_profile_backfill_status CHECK (status IN ('PAUSED', 'RUNNING', 'COMPLETED')),
    CONSTRAINT ck_user_profile_backfill_cursor CHECK (
        upper_bound_id >= 0 AND cursor_id >= 0 AND cursor_id <= upper_bound_id
    ),
    CONSTRAINT ck_user_profile_backfill_counts CHECK (
        scanned_count >= 0
        AND prepared_count >= 0 AND attached_count >= 0
        AND skipped_count >= 0 AND failed_count >= 0
        AND prepared_count + attached_count + skipped_count + failed_count = scanned_count
    ),
    CONSTRAINT ck_user_profile_backfill_lease CHECK (
        (status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND lease_token REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
        OR (status IN ('PAUSED', 'COMPLETED') AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
