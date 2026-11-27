CREATE TABLE signup_phone_challenge (
    session_digest BINARY(32) PRIMARY KEY,
    phone_digest BINARY(32) NOT NULL,
    challenge_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX ix_signup_challenge_expiry (expires_at)
);

CREATE TABLE signup_phone_rate_limit (
    bucket BINARY(32) PRIMARY KEY,
    expires_at DATETIME(6) NOT NULL,
    hits INT NOT NULL,
    INDEX ix_signup_rate_expiry (expires_at)
);

-- 지급한 번호의 중복 판별값만 보관한다. 탈퇴로 삭제되는 회원 엔티티를 참조하지 않는다.
CREATE TABLE invite_rewarded_phone (
    phone_digest BINARY(32) PRIMARY KEY
);
