-- 식당 음식점 분류(음식점·카페·주점) (#211)
-- 저장 컬렉션 상세(SAVED-008)의 분류 필터 축. 기존 장르(genre)·음식 카테고리(food_category)와 별개의 상위 구분이다.
ALTER TABLE restaurant
    ADD COLUMN place_type VARCHAR(20) NULL AFTER food_category;

-- 기존 등록 식당은 전부 음식점에 해당한다. 분류가 달라지는 식당은 어드민 수정 또는 후속 migration으로 정정한다.
UPDATE restaurant
SET place_type = 'RESTAURANT'
WHERE place_type IS NULL;

ALTER TABLE restaurant
    MODIFY COLUMN place_type VARCHAR(20) NOT NULL;
