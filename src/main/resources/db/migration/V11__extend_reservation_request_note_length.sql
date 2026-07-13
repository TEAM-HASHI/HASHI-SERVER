-- 예약 요청사항 최대 길이를 1000자로 확장 (#113)
-- 요청 검증(@Size max=1000, 7f47ccb)만 늘어나고 컬럼이 VARCHAR(500)로 남아
-- 501~1000자 요청이 저장 시점에 Data truncation(500 에러)으로 실패하던 불일치를 해소한다.
ALTER TABLE reservation
    MODIFY COLUMN request_note VARCHAR(1000) NULL;
