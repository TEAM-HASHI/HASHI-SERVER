-- foodCategory 자유 텍스트 전환 (#145)
-- 기존 값은 @Enumerated(STRING)이 저장한 enum name(SUSHI 등)이라, 사용자 응답에 노출되던
-- 표시 문자열(enum description)로 변환한다. 컬럼 타입(varchar(20) NOT NULL)은 그대로 유지한다.
UPDATE restaurant
SET food_category = CASE food_category
    WHEN 'SUSHI' THEN '초밥'
    WHEN 'NOODLE' THEN '면류'
    WHEN 'RICE_BOWL' THEN '덮밥류'
    WHEN 'NABE' THEN '나베/냄비류'
    WHEN 'FRIED' THEN '튀김류'
    WHEN 'GRILL' THEN '철판/구이류'
    WHEN 'ETC' THEN '기타'
    ELSE food_category
END;
