-- 회원 탈퇴(#243) — 탈퇴 회원의 리뷰 작성자 표시용 익명 닉네임(REVIEW_POLICY 후보 중 하나).
-- 활성 회원은 NULL이고 여러 탈퇴 회원이 같은 값을 가질 수 있어 유니크가 아니다.
ALTER TABLE users
    ADD COLUMN anonymous_nickname VARCHAR(50) NULL AFTER nickname;
