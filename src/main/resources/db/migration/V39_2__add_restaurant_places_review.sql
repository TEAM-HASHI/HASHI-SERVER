ALTER TABLE restaurant_location
    ADD COLUMN google_place_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER source,
    ADD COLUMN places_attributions JSON NULL AFTER google_place_id,
    DROP CHECK chk_location_payload,
    ADD CONSTRAINT chk_location_payload CHECK (
        (latitude IS NOT NULL AND longitude IS NOT NULL
            AND source IS NOT NULL AND source IN ('GOOGLE_GEOCODING', 'GOOGLE_PLACES', 'ADMIN')
            AND CHAR_LENGTH(source) = CHAR_LENGTH(TRIM(source))
            AND obtained_at IS NOT NULL AND valid_until IS NOT NULL AND obtained_at < valid_until
            AND ((source = 'GOOGLE_PLACES' AND google_place_id IS NOT NULL
                    AND CHAR_LENGTH(google_place_id) BETWEEN 1 AND 255
                    AND CHAR_LENGTH(google_place_id) = CHAR_LENGTH(TRIM(google_place_id))
                    AND places_attributions IS NOT NULL AND JSON_TYPE(places_attributions) = 'ARRAY')
                OR (source <> 'GOOGLE_PLACES' AND google_place_id IS NULL AND places_attributions IS NULL)))
        OR (latitude IS NULL AND longitude IS NULL AND source IS NULL AND google_place_id IS NULL
            AND places_attributions IS NULL
            AND obtained_at IS NULL AND valid_until IS NULL)
    );

ALTER TABLE restaurant_location_job
    ADD COLUMN operation VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'GEOCODING'
        AFTER request_id,
    ADD COLUMN google_place_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER operation,
    ADD CONSTRAINT chk_location_job_operation CHECK (
        (operation = 'GEOCODING' AND google_place_id IS NULL)
        OR (operation = 'PLACE_DETAILS' AND google_place_id IS NOT NULL
            AND CHAR_LENGTH(google_place_id) BETWEEN 1 AND 255
            AND CHAR_LENGTH(google_place_id) = CHAR_LENGTH(TRIM(google_place_id)))
    ),
    ADD INDEX idx_location_job_operation_due (operation, state, next_attempt_at, id);

CREATE TABLE restaurant_places_budget (
    operation VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BIT(1) NOT NULL,
    daily_limit INT NOT NULL,
    minute_limit INT NOT NULL,
    budget_day DATE NULL,
    daily_used INT NOT NULL,
    minute_window_start DATETIME(6) NULL,
    minute_used INT NOT NULL,
    blocked_until DATETIME(6) NULL,
    PRIMARY KEY (operation),
    CONSTRAINT chk_places_budget_operation CHECK (operation IN ('SEARCH', 'DETAILS')),
    CONSTRAINT chk_places_budget_limits CHECK (
        daily_limit BETWEEN 0 AND 100000 AND minute_limit BETWEEN 0 AND 10000
        AND daily_used >= 0 AND minute_used >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Places is opt-in. Enabling provider configuration never changes these durable controls.
INSERT INTO restaurant_places_budget
    (operation, enabled, daily_limit, minute_limit, daily_used, minute_used)
VALUES ('SEARCH', b'0', 100, 10, 0, 0),
       ('DETAILS', b'0', 100, 10, 0, 0);
