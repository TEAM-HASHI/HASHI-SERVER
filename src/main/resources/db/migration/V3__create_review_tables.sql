CREATE TABLE review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    writer_id BIGINT NOT NULL,
    rating INT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_review_restaurant_created_id (restaurant_id, created_at, id),
    INDEX idx_review_restaurant_rating_id (restaurant_id, rating, id),
    INDEX idx_review_writer_id (writer_id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_keyword (
    review_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    keyword VARCHAR(50) NOT NULL,
    PRIMARY KEY (review_id, display_order),
    CONSTRAINT fk_review_keyword_review
        FOREIGN KEY (review_id) REFERENCES review (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_image (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    file_key VARCHAR(500) NOT NULL,
    display_order INT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_review_image_review_order (review_id, display_order),
    CONSTRAINT fk_review_image_review
        FOREIGN KEY (review_id) REFERENCES review (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
