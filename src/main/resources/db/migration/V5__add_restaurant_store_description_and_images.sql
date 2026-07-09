ALTER TABLE restaurant
    ADD COLUMN store_description TEXT;

CREATE TABLE restaurant_image (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    file_key VARCHAR(500) NOT NULL,
    display_order INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_restaurant_image_display_order UNIQUE (restaurant_id, display_order),
    CONSTRAINT fk_restaurant_image_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
