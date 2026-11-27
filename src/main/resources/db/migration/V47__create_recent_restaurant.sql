CREATE TABLE recent_restaurant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    viewed_at DATETIME(6) NULL COMMENT 'UTC. NULL for imported device history without a timestamp',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recent_restaurant_user_restaurant (user_id, restaurant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
