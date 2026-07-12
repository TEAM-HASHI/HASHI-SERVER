-- Development-only sample data for restaurant APIs.
-- Flyway must create the schema first. Run this file manually only against a dev database.

DELETE FROM restaurant_curation_type WHERE restaurant_id BETWEEN 1001 AND 1002;
DELETE FROM restaurant_hashtag WHERE restaurant_id BETWEEN 1001 AND 1002;
DELETE FROM restaurant_menu WHERE restaurant_id BETWEEN 1001 AND 1002;
DELETE FROM restaurant_business_hour WHERE restaurant_id BETWEEN 1001 AND 1002;
DELETE FROM restaurant_image WHERE restaurant_id BETWEEN 1001 AND 1002;
DELETE FROM restaurant WHERE id BETWEEN 1001 AND 1002;

INSERT INTO restaurant (
    id, created_at, updated_at, name, local_name, summary, description, address, area,
    genre, food_category, price_currency, price_min, price_max,
    rating_sum, review_count, rating, active
) VALUES
    (1001, NOW(6), NOW(6), '히마와리 스시 신도심점', 'Himawari Sushi',
     '현지에서 사랑받는 스시 전문점', '신선한 제철 생선을 사용하는 스시 전문점입니다.',
     'Tokyo Shintoshin 1-1', '도쿄', 'SUSHI', 'SUSHI', 'JPY', 1500.00, 5000.00,
     0, 0, 0.0, TRUE),
    (1002, NOW(6), NOW(6), '아키토라 라멘', 'Akitora Ramen',
     '진한 육수가 특징인 라멘집', '매일 직접 끓인 육수와 생면을 제공합니다.',
     'Tokyo Shibuya 2-3', '도쿄', 'NOODLE', 'NOODLE', 'JPY', 1000.00, 2500.00,
     0, 0, 0.0, TRUE);

INSERT INTO restaurant_image (
    restaurant_id, created_at, updated_at, file_key, display_order
) VALUES
    (1001, NOW(6), NOW(6), 'uploads/restaurants/sample/himawari-01.jpg', 1),
    (1001, NOW(6), NOW(6), 'uploads/restaurants/sample/himawari-02.jpg', 2),
    (1002, NOW(6), NOW(6), 'uploads/restaurants/sample/akitora-01.jpg', 1);

INSERT INTO restaurant_menu (
    restaurant_id, created_at, updated_at, name, description,
    image_key, price_currency, price_amount, is_main
) VALUES
    (1001, NOW(6), NOW(6), '오마카세 스시', '셰프 추천 스시 코스',
     'uploads/restaurant-menus/sample/omakase.jpg', 'JPY', 4800.00, TRUE),
    (1001, NOW(6), NOW(6), '연어 니기리', '신선한 연어 니기리',
     'uploads/restaurant-menus/sample/salmon.jpg', 'JPY', 1500.00, FALSE),
    (1002, NOW(6), NOW(6), '돈코츠 라멘', '진한 돼지뼈 육수 라멘',
     'uploads/restaurant-menus/sample/tonkotsu.jpg', 'JPY', 1200.00, TRUE);

INSERT INTO restaurant_business_hour (
    restaurant_id, day_of_week, open_time, close_time,
    break_start, break_end, is_closed
) VALUES
    (1001, 'MONDAY', '10:00:00', '22:00:00', '15:00:00', '16:00:00', FALSE),
    (1001, 'TUESDAY', '10:00:00', '22:00:00', '15:00:00', '16:00:00', FALSE),
    (1001, 'WEDNESDAY', '10:00:00', '22:00:00', '15:00:00', '16:00:00', FALSE),
    (1001, 'THURSDAY', '10:00:00', '22:00:00', '15:00:00', '16:00:00', FALSE),
    (1001, 'FRIDAY', '10:00:00', '22:00:00', '15:00:00', '16:00:00', FALSE),
    (1001, 'SATURDAY', '11:00:00', '22:00:00', NULL, NULL, FALSE),
    (1001, 'SUNDAY', NULL, NULL, NULL, NULL, TRUE),
    (1002, 'MONDAY', '11:00:00', '21:30:00', NULL, NULL, FALSE),
    (1002, 'TUESDAY', '11:00:00', '21:30:00', NULL, NULL, FALSE),
    (1002, 'WEDNESDAY', '11:00:00', '21:30:00', NULL, NULL, FALSE),
    (1002, 'THURSDAY', '11:00:00', '21:30:00', NULL, NULL, FALSE),
    (1002, 'FRIDAY', '11:00:00', '21:30:00', NULL, NULL, FALSE),
    (1002, 'SATURDAY', '11:00:00', '21:30:00', NULL, NULL, FALSE),
    (1002, 'SUNDAY', NULL, NULL, NULL, NULL, TRUE);

INSERT INTO restaurant_hashtag (restaurant_id, hashtag) VALUES
    (1001, '오마카세'),
    (1001, '데이트'),
    (1002, '라멘'),
    (1002, '현지맛집');

INSERT INTO restaurant_curation_type (restaurant_id, curation_type) VALUES
    (1001, 'SNS_HOT'),
    (1001, 'HASHI_PICK'),
    (1002, 'POPULAR');
