-- No coordinates, tourist regions or geocoding jobs are seeded here.
CREATE TABLE restaurant_location (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    latitude DECIMAL(9, 6),
    longitude DECIMAL(10, 6),
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin,
    address_revision BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obtained_at DATETIME(6),
    valid_until DATETIME(6),
    next_attempt_at DATETIME(6),
    lock_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT chk_location_revision CHECK (address_revision > 0 AND lock_version >= 0),
    CONSTRAINT chk_location_status CHECK (
        status IN ('PENDING', 'READY', 'RETRY_WAIT', 'REVIEW_REQUIRED', 'FAILED')
        AND CHAR_LENGTH(status) = CHAR_LENGTH(TRIM(status))
    ),
    CONSTRAINT chk_location_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    ),
    CONSTRAINT chk_location_ready CHECK (
        (status = 'READY' AND latitude IS NOT NULL AND longitude IS NOT NULL
            AND source IS NOT NULL AND source IN ('GOOGLE_GEOCODING', 'OPERATOR')
            AND CHAR_LENGTH(source) = CHAR_LENGTH(TRIM(source))
            AND obtained_at IS NOT NULL AND valid_until IS NOT NULL AND obtained_at < valid_until)
        OR (status <> 'READY' AND latitude IS NULL AND longitude IS NULL
            AND source IS NULL AND obtained_at IS NULL AND valid_until IS NULL)
    ),
    CONSTRAINT chk_location_retry CHECK (
        (status = 'RETRY_WAIT' AND next_attempt_at IS NOT NULL)
        OR (status <> 'RETRY_WAIT' AND next_attempt_at IS NULL)
    ),
    INDEX idx_location_status_coordinates (status, latitude, longitude),
    INDEX idx_location_status_expiry (status, valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE map_region (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    cluster_latitude DECIMAL(9, 6) NOT NULL,
    cluster_longitude DECIMAL(10, 6) NOT NULL,
    south DECIMAL(9, 6) NOT NULL,
    north DECIMAL(9, 6) NOT NULL,
    west DECIMAL(10, 6) NOT NULL,
    east DECIMAL(10, 6) NOT NULL,
    display_order INT NOT NULL,
    active BIT(1) NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    CONSTRAINT uq_map_region_code UNIQUE (code),
    CONSTRAINT chk_map_region_code CHECK (REGEXP_LIKE(code, '^[A-Z][A-Z0-9_]{0,39}$', 'c')),
    -- Unicode whitespace plus Java's U+001C..U+001F separators, matching MapRegion.BLANK_NAME.
    CONSTRAINT chk_map_region_name CHECK (REGEXP_LIKE(name, '[^[:space:]\\x{001C}-\\x{001F}]')),
    CONSTRAINT chk_map_region_order CHECK (display_order >= 0),
    CONSTRAINT chk_map_region_bounds CHECK (
        south >= -90 AND north <= 90 AND south < north
        AND west >= -180 AND east <= 180 AND west < east
    ),
    CONSTRAINT chk_map_region_position CHECK (
        cluster_latitude BETWEEN south AND north AND cluster_longitude BETWEEN west AND east
    ),
    INDEX idx_map_region_active_order (active, display_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- location is an owned child of Restaurant. MapRegion is a separate aggregate referenced only by ID.
ALTER TABLE restaurant
    ADD COLUMN location_id BIGINT NULL,
    ADD COLUMN map_region_id BIGINT NULL,
    ADD CONSTRAINT uq_restaurant_location UNIQUE (location_id),
    ADD CONSTRAINT fk_restaurant_location FOREIGN KEY (location_id) REFERENCES restaurant_location (id),
    ADD CONSTRAINT chk_restaurant_map_region CHECK (map_region_id IS NULL OR map_region_id > 0),
    ADD INDEX idx_restaurant_map_region (map_region_id, deleted, id);
