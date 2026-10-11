-- Only control data and job IDs; never copy addresses or Google results into run history.
CREATE TABLE restaurant_location_maintenance_run (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    after_id BIGINT NOT NULL,
    upper_id BIGINT NOT NULL,
    cursor_id BIGINT NOT NULL,
    as_of DATETIME(6) NOT NULL,
    refresh_before DATETIME(6) NOT NULL,
    max_registrations INT NOT NULL,
    max_calls INT NOT NULL,
    scanned BIGINT NOT NULL DEFAULT 0,
    enqueued INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_location_run_mode CHECK (mode IN ('BACKFILL', 'REFRESH')
        AND CHAR_LENGTH(mode) = CHAR_LENGTH(TRIM(mode))),
    CONSTRAINT chk_location_run_state CHECK (state IN ('ACTIVE', 'STOPPED', 'SCANNED', 'LIMIT_REACHED')
        AND CHAR_LENGTH(state) = CHAR_LENGTH(TRIM(state))),
    CONSTRAINT chk_location_run_range CHECK (after_id >= 0 AND upper_id >= after_id
        AND cursor_id BETWEEN after_id AND upper_id AND refresh_before > as_of),
    CONSTRAINT chk_location_run_limits CHECK (max_registrations BETWEEN 1 AND 10000
        AND max_calls BETWEEN 0 AND 80000 AND scanned >= 0 AND enqueued >= 0
        AND enqueued <= max_registrations AND enqueued * 8 <= max_calls AND scanned >= enqueued)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE restaurant_location_maintenance_job (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id BIGINT NOT NULL,
    PRIMARY KEY (run_id, job_id),
    CONSTRAINT uq_location_maintenance_job UNIQUE (job_id),
    CONSTRAINT fk_location_maintenance_run FOREIGN KEY (run_id)
        REFERENCES restaurant_location_maintenance_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
