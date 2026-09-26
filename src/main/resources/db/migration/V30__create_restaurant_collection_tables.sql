-- 식당 컬렉션(SAVED-004~008) (#216)
-- 회원이 식당을 묶어 저장하는 컬렉션과 저장 식당 매핑. user_id·restaurant_id는 타 애그리거트라 FK 없이 값만 보관한다.
CREATE TABLE restaurant_collection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    -- 테이블 기본 utf8mb4_unicode_ci(UCA 4.0.0)는 이모지 등 보조 평면 문자를 모두 같은 가중치로 비교해
    -- '🍣 맛집'과 '🍺 맛집'을 같은 이름으로 본다. 컬렉션명 유니크 판정만 UCA 9.0.0 기반 as_ci로 비교한다
    -- (이모지·악센트는 구분하고, 대소문자·전각/반각 같은 3차 차이는 기존처럼 같은 이름으로 본다).
    name VARCHAR(20) COLLATE utf8mb4_0900_as_ci NOT NULL,
    color VARCHAR(20) NOT NULL,
    description VARCHAR(100) NULL,
    visibility VARCHAR(20) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_restaurant_collection_user_name UNIQUE (user_id, name),
    INDEX idx_restaurant_collection_user_id (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 저장 식당 — 컬렉션 애그리거트 자식(같은 애그리거트라 FK 허용). 같은 컬렉션에 같은 식당은 한 번만 저장된다.
CREATE TABLE saved_restaurant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    collection_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_saved_restaurant_collection_restaurant UNIQUE (collection_id, restaurant_id),
    INDEX idx_saved_restaurant_restaurant_id (restaurant_id),
    CONSTRAINT fk_saved_restaurant_collection
        FOREIGN KEY (collection_id) REFERENCES restaurant_collection (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
