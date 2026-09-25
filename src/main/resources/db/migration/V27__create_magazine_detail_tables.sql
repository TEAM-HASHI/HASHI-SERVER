-- 매거진 상세(MAG-002) (#208)
-- magazine: 본문 추가. content는 어드민 등록 계약 확정 전이라 NULL 허용.
ALTER TABLE magazine
    ADD COLUMN content VARCHAR(2000) NULL AFTER instagram_redirect_url;

-- 매거진 메타데이터(집계 카운터) — magazine과 1:1. 카운터는 비동기 원자 UPDATE로만 갱신한다.
CREATE TABLE meta_magazine (
    magazine_id BIGINT NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (magazine_id),
    INDEX idx_meta_magazine_like_count_id (like_count, magazine_id),
    CONSTRAINT fk_meta_magazine_magazine
        FOREIGN KEY (magazine_id) REFERENCES magazine (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기존 매거진에 카운터 행을 만들어 둔다(이후 생성분은 서비스가 같은 트랜잭션에서 만든다)
INSERT INTO meta_magazine (magazine_id, like_count, created_at, updated_at)
SELECT id, 0, NOW(6), NOW(6)
FROM magazine;

-- 카드뉴스 이미지(캐러셀) — 매거진 애그리거트 자식, 같은 애그리거트라 FK 허용
CREATE TABLE magazine_card_news (
    id BIGINT NOT NULL AUTO_INCREMENT,
    magazine_id BIGINT NOT NULL,
    file_key VARCHAR(500) NOT NULL,
    display_order INT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_magazine_card_news_magazine_order (magazine_id, display_order),
    CONSTRAINT fk_magazine_card_news_magazine
        FOREIGN KEY (magazine_id) REFERENCES magazine (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 해시태그 — restaurant_hashtag와 같은 컬렉션 테이블 규격
CREATE TABLE magazine_hashtag (
    magazine_id BIGINT NOT NULL,
    hashtag VARCHAR(20) NOT NULL,
    PRIMARY KEY (magazine_id, hashtag),
    CONSTRAINT fk_magazine_hashtag_magazine
        FOREIGN KEY (magazine_id) REFERENCES magazine (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 연결 식당 매핑(architecture.md §5-2) — restaurant_id는 타 애그리거트라 FK 없이 값만 보관한다
CREATE TABLE magazine_restaurant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    magazine_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_magazine_restaurant_magazine_restaurant UNIQUE (magazine_id, restaurant_id),
    CONSTRAINT fk_magazine_restaurant_magazine
        FOREIGN KEY (magazine_id) REFERENCES magazine (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 리액션(좋아요) — 회원·매거진·종류당 1행. 취소는 status=INACTIVE로 남긴다. user_id는 타 애그리거트라 FK 없이 값만 보관한다
CREATE TABLE magazine_reaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    magazine_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_magazine_reaction_magazine_user_type UNIQUE (magazine_id, user_id, reaction_type),
    CONSTRAINT fk_magazine_reaction_magazine
        FOREIGN KEY (magazine_id) REFERENCES magazine (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
