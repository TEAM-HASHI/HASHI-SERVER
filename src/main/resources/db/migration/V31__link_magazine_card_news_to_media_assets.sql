-- 카드뉴스 이미지를 media asset으로 연결 (#210)
-- 식당 이미지(V18)와 같은 규격: legacy key 또는 public asset ID 중 하나는 있어야 하고,
-- asset ID는 모듈 FK 없이 ASCII UUID 값과 local unique로만 보관한다.
ALTER TABLE magazine_card_news
    MODIFY COLUMN file_key VARCHAR(500) NULL,
    ADD COLUMN image_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER file_key,
    ADD CONSTRAINT ck_magazine_card_news_source CHECK (
        (file_key IS NULL OR CHAR_LENGTH(TRIM(file_key)) > 0)
        AND (file_key IS NOT NULL OR image_asset_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_magazine_card_news_asset_id CHECK (
        image_asset_id IS NULL
        OR image_asset_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    ADD UNIQUE INDEX uq_magazine_card_news_asset_id (image_asset_id);
