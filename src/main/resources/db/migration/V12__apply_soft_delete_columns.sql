-- soft delete 컬럼 통일 (#115)
-- restaurant: active(활성) → deleted(삭제)로 의미 반전 rename, review와 동일한 컬럼 규격으로 정리
ALTER TABLE restaurant
    RENAME COLUMN active TO deleted;

UPDATE restaurant
SET deleted = NOT deleted;

ALTER TABLE restaurant
    MODIFY COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    RENAME INDEX idx_restaurant_active_id TO idx_restaurant_deleted_id,
    RENAME INDEX idx_restaurant_active_rating_id TO idx_restaurant_deleted_rating_id,
    RENAME INDEX idx_restaurant_active_popular_id TO idx_restaurant_deleted_popular_id;

-- users, magazine: soft delete 컬럼 신규 추가 (기존 행은 모두 미삭제 상태)
ALTER TABLE users
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE magazine
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
