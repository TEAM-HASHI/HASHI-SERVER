CREATE TABLE restaurant_business_hour (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    open_time TIME(6),
    close_time TIME(6),
    last_order_time TIME(6),
    closed BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_restaurant_business_hour_day UNIQUE (restaurant_id, day_of_week),
    CONSTRAINT fk_restaurant_business_hour_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
