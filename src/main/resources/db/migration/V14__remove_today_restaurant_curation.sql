-- TODAY_RESTAURANT 큐레이션 폐기 (#154)
-- 랜덤 추천이 전체 활성 식당 대상으로 바뀌면서 큐레이션 타입이 enum에서 제거됐다.
-- 남은 행이 있으면 조회 시 enum 파싱이 깨지므로 데이터도 함께 정리한다(prod는 0행, dev는 더미 데이터 존재).
DELETE FROM restaurant_curation_type
WHERE curation_type = 'TODAY_RESTAURANT';
