-- V31~V35를 먼저 병합·적용한다. 아래 유형은 환경 독립적인 제어 데이터이며 약관 본문은 적재하지 않는다.
CREATE TABLE support_terms_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(100) NOT NULL,
    version VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_date DATE NOT NULL,
    clauses_json MEDIUMTEXT NOT NULL,
    published_at DATETIME(6) NULL,
    UNIQUE KEY uk_support_terms_type_version (type, version),
    UNIQUE KEY uk_support_terms_type_id (type, id),
    INDEX idx_support_terms_history (type, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE support_terms_type (
    type VARCHAR(40) NOT NULL PRIMARY KEY,
    current_version_id BIGINT NULL,
    CONSTRAINT fk_support_terms_current FOREIGN KEY (type, current_version_id)
        REFERENCES support_terms_version (type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO support_terms_type (type) VALUES
('SERVICE_TERMS'), ('PRIVACY_POLICY'), ('PERSONAL_DATA_COLLECTION'),
('PERSONAL_DATA_THIRD_PARTY'), ('RESERVATION_REFUND_POLICY'),
('REVIEW_POLICY'), ('POINT_TERMS'), ('SERVICE_POLICY');
