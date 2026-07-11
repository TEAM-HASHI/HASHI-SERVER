-- Precondition: every review must have a reservation_id and each reservation must have at most one review.
-- Resolve inconsistent legacy rows before applying this migration instead of deleting or guessing data here.
CREATE TEMPORARY TABLE v9_review_schema_guard (
    violation VARCHAR(100) NOT NULL,
    valid TINYINT NOT NULL,
    CONSTRAINT chk_v9_review_schema_guard CHECK (valid = 1)
);

INSERT INTO v9_review_schema_guard (violation, valid)
SELECT 'review.reservation_id contains null', 0
WHERE EXISTS (
    SELECT 1
    FROM review
    WHERE reservation_id IS NULL
);

INSERT INTO v9_review_schema_guard (violation, valid)
SELECT 'review.reservation_id contains duplicates', 0
WHERE EXISTS (
    SELECT 1
    FROM review
    GROUP BY reservation_id
    HAVING COUNT(*) > 1
);

INSERT INTO v9_review_schema_guard (violation, valid)
SELECT 'review_keyword.keyword exceeds 30 characters', 0
WHERE EXISTS (
    SELECT 1
    FROM review_keyword
    WHERE CHAR_LENGTH(keyword) > 30
);

INSERT INTO v9_review_schema_guard (violation, valid)
SELECT 'review_keyword.display_order is invalid', 0
WHERE EXISTS (
    SELECT 1
    FROM review_keyword
    GROUP BY review_id
    HAVING MIN(display_order) <> 0
        OR MAX(display_order) <> COUNT(*) - 1
        OR MAX(display_order) > 2
);

INSERT INTO v9_review_schema_guard (violation, valid)
SELECT 'review_image.display_order is invalid', 0
WHERE EXISTS (
    SELECT 1
    FROM review_image
    WHERE display_order < 0 OR display_order > 9
);

INSERT INTO v9_review_schema_guard (violation, valid)
SELECT 'review_image.display_order contains duplicates', 0
WHERE EXISTS (
    SELECT 1
    FROM review_image
    GROUP BY review_id, display_order
    HAVING COUNT(*) > 1
);

DROP TEMPORARY TABLE v9_review_schema_guard;

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
