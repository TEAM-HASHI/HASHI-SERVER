CREATE TABLE restaurant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    name VARCHAR(100) NOT NULL,
    local_name VARCHAR(100),
    description VARCHAR(500),
    address VARCHAR(255) NOT NULL,
    area VARCHAR(100),
    genre VARCHAR(30) NOT NULL,
    thumbnail_file_key VARCHAR(500),
    reservation_fee BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    min_price DECIMAL(15, 2),
    max_price DECIMAL(15, 2),
    rating DOUBLE NOT NULL,
    review_count BIGINT NOT NULL,
    saved_count BIGINT NOT NULL,
    popularity_score BIGINT NOT NULL,
    active BIT(1) NOT NULL,
    available_date DATE,
    available_start_time TIME(6),
    available_end_time TIME(6),
    PRIMARY KEY (id),
    INDEX idx_restaurant_active_id (active, id),
    INDEX idx_restaurant_active_popularity_id (active, popularity_score, id),
    INDEX idx_restaurant_active_rating_id (active, rating, id),
    INDEX idx_restaurant_genre (genre)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE restaurant_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    image_file_key VARCHAR(500),
    currency VARCHAR(10) NOT NULL,
    price DECIMAL(15, 2),
    representative BIT(1) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_restaurant_menu_restaurant_id_id (restaurant_id, id),
    CONSTRAINT fk_restaurant_menu_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE restaurant_tag (
    restaurant_id BIGINT NOT NULL,
    tag VARCHAR(50) NOT NULL,
    PRIMARY KEY (restaurant_id, tag),
    CONSTRAINT fk_restaurant_tag_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE restaurant_curation_type (
    restaurant_id BIGINT NOT NULL,
    curation_type VARCHAR(30) NOT NULL,
    PRIMARY KEY (restaurant_id, curation_type),
    INDEX idx_restaurant_curation_type_curation_restaurant (curation_type, restaurant_id),
    CONSTRAINT fk_restaurant_curation_type_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
