-- 현재 develop V32 이후 적용한다. 미병합 지도 migration은 V38 이상으로 재번호한다.
CREATE TABLE support_notice (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    body_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(6) NULL,
    modified_after_publication_at DATETIME(6) NULL,
    INDEX idx_support_notice_public (deleted, published_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE support_notice_image (
    notice_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    image_asset_id CHAR(36) NOT NULL,
    PRIMARY KEY (notice_id, display_order),
    UNIQUE KEY uk_support_notice_image_asset (image_asset_id),
    CONSTRAINT fk_support_notice_image_notice FOREIGN KEY (notice_id) REFERENCES support_notice (id),
    CONSTRAINT ck_support_notice_image_order CHECK (display_order BETWEEN 0 AND 9)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
