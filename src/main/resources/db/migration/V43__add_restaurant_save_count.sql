-- 식당 저장 수(#263) — 회원 한 명당 최대 1로 집계한다(여러 컬렉션에 저장해도 1, 마지막 저장 관계 삭제 시 -1).
-- user 모듈이 RestaurantPort로 증감하는 카운터이며 restaurant는 읽기만 한다. FK는 두지 않는다.
ALTER TABLE restaurant
    ADD COLUMN save_count BIGINT NOT NULL DEFAULT 0;

-- 일회성 backfill: 기존 저장 관계를 회원 기준 distinct로 센다(조회만, 제약 없음).
UPDATE restaurant r
SET r.save_count = (
    SELECT COUNT(DISTINCT c.user_id)
    FROM saved_restaurant s
    JOIN restaurant_collection c ON c.id = s.collection_id
    WHERE s.restaurant_id = r.id
);
