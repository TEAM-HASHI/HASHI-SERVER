-- Minimal job history: no address, provider JSON, coordinates or request URI.
CREATE TABLE restaurant_location_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    address_revision BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    reserved_until DATETIME(6) NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_location_job_request UNIQUE (request_id),
    CONSTRAINT fk_location_job_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id),
    CONSTRAINT chk_location_job_attempt CHECK (address_revision > 0 AND attempt >= 0),
    CONSTRAINT chk_location_job_state CHECK (
        state IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'SUCCEEDED', 'REVIEW_REQUIRED', 'FAILED', 'SUPERSEDED')
        AND CHAR_LENGTH(state) = CHAR_LENGTH(TRIM(state))
    ),
    CONSTRAINT chk_location_job_lease CHECK (
        (state = 'LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND reserved_until IS NOT NULL AND reserved_until = lease_until AND attempt > 0)
        OR (state <> 'LEASED' AND lease_token IS NULL AND lease_until IS NULL)
    ),
    INDEX idx_location_job_due (state, next_attempt_at, id),
    INDEX idx_location_job_lease (state, lease_until, id),
    INDEX idx_location_job_restaurant (restaurant_id, state, id),
    INDEX idx_location_job_reservation (reserved_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE restaurant_geocoding_budget (
    id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL,
    daily_limit INT NOT NULL,
    max_concurrent INT NOT NULL,
    budget_day DATE NULL,
    reserved_calls INT NOT NULL,
    blocked_until DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_geocoding_budget_singleton CHECK (id = 1),
    CONSTRAINT chk_geocoding_budget_limits CHECK (
        daily_limit BETWEEN 0 AND 100000 AND max_concurrent BETWEEN 0 AND 4 AND reserved_calls >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Environment-independent fail-closed control state; see ADR 0003. Never reset on startup.
INSERT INTO restaurant_geocoding_budget (id, enabled, daily_limit, max_concurrent, reserved_calls)
VALUES (1, b'0', 0, 0, 0);
