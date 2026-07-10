-- Precondition: every review must have a reservation_id and each reservation must have at most one review.
-- Resolve inconsistent legacy rows before applying this migration instead of deleting or guessing data here.
ALTER TABLE review
    DROP INDEX uk_review_active_reservation_id,
    DROP COLUMN active_reservation_id,
    RENAME COLUMN writer_id TO user_id,
    RENAME COLUMN active TO deleted;

UPDATE review
SET deleted = NOT deleted;

ALTER TABLE review
    RENAME INDEX idx_review_writer_id TO idx_review_user_id,
    MODIFY COLUMN reservation_id BIGINT NOT NULL,
    MODIFY COLUMN user_id BIGINT NOT NULL,
    MODIFY COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uk_review_reservation_id UNIQUE (reservation_id);

ALTER TABLE review_keyword
    MODIFY COLUMN keyword VARCHAR(30) NOT NULL,
    ADD CONSTRAINT chk_review_keyword_display_order
        CHECK (display_order BETWEEN 0 AND 2);

ALTER TABLE review_image
    MODIFY COLUMN file_key VARCHAR(500) NOT NULL COMMENT 'S3 object key',
    DROP INDEX idx_review_image_review_order,
    ADD CONSTRAINT uk_review_image_display_order UNIQUE (review_id, display_order),
    ADD CONSTRAINT chk_review_image_display_order
        CHECK (display_order BETWEEN 0 AND 9);
