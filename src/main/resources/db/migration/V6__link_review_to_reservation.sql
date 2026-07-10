ALTER TABLE review
    ADD COLUMN reservation_id BIGINT NULL AFTER id,
    ADD COLUMN active_reservation_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN active = 1 THEN reservation_id ELSE NULL END
        ) STORED,
    ADD INDEX idx_review_reservation_id (reservation_id),
    ADD CONSTRAINT uk_review_active_reservation_id UNIQUE (active_reservation_id);
