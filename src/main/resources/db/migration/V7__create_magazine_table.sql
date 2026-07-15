CREATE TABLE magazine (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    title VARCHAR(150) NOT NULL,
    banner_key VARCHAR(500) NOT NULL,
    instagram_redirect_url VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
