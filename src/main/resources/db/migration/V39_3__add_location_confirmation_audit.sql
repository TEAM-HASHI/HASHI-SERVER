-- Keep provider provenance separate from the administrator's candidate selection.
-- Historical selections have no known administrator or confirmation time; do not fabricate them.
ALTER TABLE restaurant_location
    ADD COLUMN confirmation_method VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN confirmed_by_admin_id BIGINT NULL,
    ADD COLUMN confirmed_at DATETIME(6) NULL,
    ADD COLUMN confirmed_revision BIGINT NULL,
    ADD CONSTRAINT chk_location_confirmation CHECK (
        (confirmation_method IS NULL AND confirmed_by_admin_id IS NULL
            AND confirmed_at IS NULL AND confirmed_revision IS NULL)
        OR (confirmation_method IS NOT NULL AND confirmation_method = 'AUTOMATIC'
            AND CHAR_LENGTH(confirmation_method) = CHAR_LENGTH(TRIM(confirmation_method))
            AND confirmed_by_admin_id IS NULL
            AND ((confirmed_at IS NULL AND confirmed_revision IS NULL)
                OR (confirmed_at IS NOT NULL AND confirmed_revision IS NOT NULL
                    AND confirmed_revision = address_revision)))
        OR (confirmation_method IS NOT NULL AND confirmation_method = 'ADMIN_PLACE_SELECTION'
            AND CHAR_LENGTH(confirmation_method) = CHAR_LENGTH(TRIM(confirmation_method))
            AND confirmed_by_admin_id IS NOT NULL
            AND confirmed_by_admin_id > 0 AND confirmed_at IS NOT NULL
            AND confirmed_revision IS NOT NULL AND confirmed_revision = address_revision)
    );

UPDATE restaurant_location
SET confirmation_method = 'AUTOMATIC'
WHERE source = 'GOOGLE_GEOCODING';
