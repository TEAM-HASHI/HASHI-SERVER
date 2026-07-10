INSERT INTO restaurant_image (restaurant_id, created_at, updated_at, file_key, display_order)
SELECT r.id,
       COALESCE(r.created_at, CURRENT_TIMESTAMP(6)),
       COALESCE(r.updated_at, r.created_at, CURRENT_TIMESTAMP(6)),
       r.thumbnail_file_key,
       1
FROM restaurant r
WHERE r.thumbnail_file_key IS NOT NULL
  AND r.thumbnail_file_key <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM restaurant_image ri
      WHERE ri.restaurant_id = r.id
  );

ALTER TABLE restaurant
    RENAME COLUMN description TO summary,
    RENAME COLUMN store_description TO description,
    RENAME COLUMN currency TO price_currency,
    RENAME COLUMN min_price TO price_min,
    RENAME COLUMN max_price TO price_max,
    ADD COLUMN food_category VARCHAR(20) NULL AFTER genre,
    ADD COLUMN rating_sum BIGINT NOT NULL DEFAULT 0 AFTER price_max;

UPDATE restaurant
SET local_name = name
WHERE local_name IS NULL OR TRIM(local_name) = '';

UPDATE restaurant
SET area = '미정'
WHERE area IS NULL OR TRIM(area) = '';

UPDATE restaurant
SET summary = name
WHERE summary IS NULL OR TRIM(summary) = '';

UPDATE restaurant
SET description = summary
WHERE description IS NULL OR TRIM(description) = '';

UPDATE restaurant
SET food_category = genre,
    price_currency = UPPER(LEFT(price_currency, 3)),
    price_min = COALESCE(price_min, 0),
    price_max = COALESCE(price_max, COALESCE(price_min, 0));

UPDATE restaurant r
LEFT JOIN (
    SELECT restaurant_id,
           COALESCE(SUM(rating), 0) AS rating_sum,
           COUNT(*) AS review_count
    FROM review
    WHERE active = TRUE
    GROUP BY restaurant_id
) review_stat ON review_stat.restaurant_id = r.id
SET r.rating_sum = COALESCE(review_stat.rating_sum, 0),
    r.review_count = COALESCE(review_stat.review_count, 0),
    r.rating = CASE
        WHEN COALESCE(review_stat.review_count, 0) = 0 THEN 0.0
        ELSE ROUND(review_stat.rating_sum / review_stat.review_count, 1)
    END;

ALTER TABLE restaurant
    DROP INDEX idx_restaurant_active_popularity_id,
    DROP INDEX idx_restaurant_active_rating_id,
    DROP COLUMN thumbnail_file_key,
    DROP COLUMN reservation_fee,
    DROP COLUMN saved_count,
    DROP COLUMN popularity_score,
    DROP COLUMN available_date,
    DROP COLUMN available_start_time,
    DROP COLUMN available_end_time,
    MODIFY COLUMN local_name VARCHAR(100) NOT NULL,
    MODIFY COLUMN genre VARCHAR(20) NOT NULL,
    MODIFY COLUMN food_category VARCHAR(20) NOT NULL,
    MODIFY COLUMN area VARCHAR(20) NOT NULL,
    MODIFY COLUMN summary VARCHAR(100) NOT NULL,
    MODIFY COLUMN description VARCHAR(500) NOT NULL,
    MODIFY COLUMN price_currency VARCHAR(3) NOT NULL,
    MODIFY COLUMN price_min DECIMAL(15, 2) NOT NULL,
    MODIFY COLUMN price_max DECIMAL(15, 2) NOT NULL,
    MODIFY COLUMN rating DECIMAL(2, 1) NOT NULL DEFAULT 0.0,
    MODIFY COLUMN review_count BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_restaurant_price
        CHECK (price_min >= 0 AND price_max >= 0 AND price_min <= price_max),
    ADD CONSTRAINT chk_restaurant_rating_sum CHECK (rating_sum >= 0),
    ADD CONSTRAINT chk_restaurant_review_count CHECK (review_count >= 0),
    ADD CONSTRAINT chk_restaurant_rating CHECK (rating >= 0.0 AND rating <= 5.0),
    ADD INDEX idx_restaurant_active_rating_id (active, rating, id),
    ADD INDEX idx_restaurant_active_popular_id (active, review_count, rating, id),
    ADD INDEX idx_restaurant_food_category (food_category);

RENAME TABLE restaurant_tag TO restaurant_hashtag;

ALTER TABLE restaurant_hashtag
    RENAME COLUMN tag TO hashtag,
    MODIFY COLUMN hashtag VARCHAR(20) NOT NULL;

UPDATE restaurant_menu
SET description = ''
WHERE description IS NULL;

ALTER TABLE restaurant_menu
    RENAME COLUMN image_file_key TO image_key,
    RENAME COLUMN currency TO price_currency,
    RENAME COLUMN price TO price_amount,
    RENAME COLUMN representative TO is_main,
    MODIFY COLUMN description VARCHAR(500) NOT NULL,
    MODIFY COLUMN price_currency VARCHAR(3) NULL;

ALTER TABLE restaurant_business_hour
    RENAME COLUMN closed TO is_closed,
    ADD COLUMN break_start TIME(6) NULL AFTER close_time,
    ADD COLUMN break_end TIME(6) NULL AFTER break_start,
    DROP COLUMN last_order_time;
