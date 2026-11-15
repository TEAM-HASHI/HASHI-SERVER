-- 지도 조회 전후 metadata/membership 변경 감지. 자식 저장/제거/이동도 부모 버전을 같은 transaction에서 갱신한다.
ALTER TABLE restaurant_collection
    ADD COLUMN collection_version BIGINT NOT NULL DEFAULT 0;
