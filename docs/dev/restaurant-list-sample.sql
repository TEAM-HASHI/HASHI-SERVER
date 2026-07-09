-- Development-only sample schema/data for restaurant list API.
-- This file is not a Flyway migration. Run it manually only against a dev database.

CREATE TABLE IF NOT EXISTS restaurant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    name VARCHAR(100) NOT NULL,
    local_name VARCHAR(100) NULL,
    description VARCHAR(500) NULL,
    address VARCHAR(255) NOT NULL,
    area VARCHAR(100) NULL,
    genre VARCHAR(30) NOT NULL,
    thumbnail_file_key VARCHAR(500) NULL,
    reservation_fee BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    min_price DECIMAL(15, 2) NULL,
    max_price DECIMAL(15, 2) NULL,
    rating DOUBLE NOT NULL,
    review_count BIGINT NOT NULL,
    saved_count BIGINT NOT NULL,
    popularity_score BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    available_date DATE NULL,
    available_start_time TIME NULL,
    available_end_time TIME NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS restaurant_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    image_file_key VARCHAR(500) NULL,
    currency VARCHAR(10) NOT NULL,
    price DECIMAL(15, 2) NULL,
    representative BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_restaurant_menu_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
);

CREATE TABLE IF NOT EXISTS restaurant_tag (
    restaurant_id BIGINT NOT NULL,
    tag VARCHAR(50) NOT NULL,
    CONSTRAINT fk_restaurant_tag_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
);

CREATE TABLE IF NOT EXISTS restaurant_curation_type (
    restaurant_id BIGINT NOT NULL,
    curation_type VARCHAR(30) NOT NULL,
    CONSTRAINT fk_restaurant_curation_type_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurant (id)
);

DELETE FROM restaurant_curation_type WHERE restaurant_id BETWEEN 1001 AND 1005;
DELETE FROM restaurant_tag WHERE restaurant_id BETWEEN 1001 AND 1005;
DELETE FROM restaurant_menu WHERE restaurant_id BETWEEN 1001 AND 1005;
DELETE FROM restaurant WHERE id BETWEEN 1001 AND 1005;

INSERT INTO restaurant (
    id, created_at, updated_at, name, local_name, description, address, area,
    genre, thumbnail_file_key, reservation_fee, currency, min_price, max_price,
    rating, review_count, saved_count, popularity_score, active,
    available_date, available_start_time, available_end_time
) VALUES
    (1001, NOW(6), NOW(6), 'Himawari Sushi Shintoshin', 'Himawari Sushi', 'Fresh sushi course near Shintoshin.', 'Tokyo Shintoshin 1-1', 'Tokyo', 'SUSHI', 'uploads/restaurants/sample/himawari.jpg', 4000, 'JPY', 1500.00, 5000.00, 4.8, 256, 180, 980, TRUE, '2026-07-19', '10:00:00', '22:00:00'),
    (1002, NOW(6), NOW(6), 'Akitora Ramen', 'Akitora Ramen', 'Rich ramen with house-made broth.', 'Tokyo Shibuya 2-3', 'Tokyo', 'NOODLE', 'uploads/restaurants/sample/akitora.jpg', 3000, 'JPY', 1000.00, 2500.00, 4.6, 182, 132, 830, TRUE, '2026-07-19', '11:00:00', '21:30:00'),
    (1003, NOW(6), NOW(6), 'Tonkatsu Hajime', 'Tonkatsu Hajime', 'Crispy tonkatsu set meals.', 'Osaka Namba 3-2', 'Osaka', 'FRIED', 'uploads/restaurants/sample/tonkatsu.jpg', 3500, 'JPY', 1200.00, 3000.00, 4.7, 144, 95, 760, TRUE, '2026-07-20', '10:30:00', '21:00:00'),
    (1004, NOW(6), NOW(6), 'Nabe Kuro', 'Nabe Kuro', 'Seasonal nabe dishes for groups.', 'Kyoto Gion 5-4', 'Kyoto', 'NABE', 'uploads/restaurants/sample/nabe-kuro.jpg', 5000, 'JPY', 2500.00, 8000.00, 4.5, 98, 77, 610, TRUE, '2026-07-20', '12:00:00', '22:00:00'),
    (1005, NOW(6), NOW(6), 'Teppan Mori', 'Teppan Mori', 'Casual teppan grill restaurant.', 'Fukuoka Tenjin 6-7', 'Fukuoka', 'GRILL', 'uploads/restaurants/sample/teppan-mori.jpg', 4500, 'JPY', 2000.00, 6500.00, 4.4, 87, 61, 540, TRUE, '2026-07-21', '11:30:00', '22:30:00');

INSERT INTO restaurant_menu (
    restaurant_id, created_at, updated_at, name, description, image_file_key, currency, price, representative
) VALUES
    (1001, NOW(6), NOW(6), 'Omakase Sushi', 'Chef selection sushi course.', 'uploads/restaurant-menus/sample/omakase.jpg', 'JPY', 4800.00, TRUE),
    (1001, NOW(6), NOW(6), 'Salmon Nigiri', 'Fresh salmon nigiri.', 'uploads/restaurant-menus/sample/salmon.jpg', 'JPY', 1500.00, FALSE),
    (1002, NOW(6), NOW(6), 'Tonkotsu Ramen', 'Pork broth ramen.', 'uploads/restaurant-menus/sample/tonkotsu.jpg', 'JPY', 1200.00, TRUE),
    (1003, NOW(6), NOW(6), 'Pork Cutlet Set', 'Tonkatsu with rice and soup.', 'uploads/restaurant-menus/sample/tonkatsu-set.jpg', 'JPY', 1800.00, TRUE),
    (1004, NOW(6), NOW(6), 'Beef Nabe', 'Hot pot with beef and vegetables.', 'uploads/restaurant-menus/sample/beef-nabe.jpg', 'JPY', 3200.00, TRUE),
    (1005, NOW(6), NOW(6), 'Mixed Teppan Grill', 'Assorted grilled meat and vegetables.', 'uploads/restaurant-menus/sample/teppan.jpg', 'JPY', 2800.00, TRUE);

INSERT INTO restaurant_tag (restaurant_id, tag) VALUES
    (1001, 'omakase'),
    (1001, 'date-night'),
    (1002, 'ramen'),
    (1002, 'local-favorite'),
    (1003, 'crispy'),
    (1004, 'group-friendly'),
    (1005, 'grill');

INSERT INTO restaurant_curation_type (restaurant_id, curation_type) VALUES
    (1001, 'SNS_HOT'),
    (1001, 'HASHI_PICK'),
    (1002, 'POPULAR'),
    (1003, 'TODAY_RESTAURANT'),
    (1004, 'HASHI_PICK'),
    (1005, 'SNS_HOT');
