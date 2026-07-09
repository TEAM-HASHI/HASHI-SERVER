CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(50) NOT NULL,
    name_eng VARCHAR(20),
    birth_date DATE NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    profile_image_key VARCHAR(500),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_nickname UNIQUE (nickname),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_account_provider_user UNIQUE (provider, provider_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE admin (
    id BIGINT NOT NULL AUTO_INCREMENT,
    login_id VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_login_id UNIQUE (login_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    reserver_name VARCHAR(50) NOT NULL,
    reservation_type VARCHAR(20) NOT NULL,
    restaurant_id BIGINT,
    restaurant_name VARCHAR(100),
    restaurant_address VARCHAR(255),
    reserved_at DATETIME(6) NOT NULL,
    adult_count INT NOT NULL,
    teen_count INT NOT NULL,
    child_count INT NOT NULL,
    reservation_status VARCHAR(20) NOT NULL,
    request_note VARCHAR(500),
    payment_status VARCHAR(20) NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    used_point BIGINT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE point_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_point_account_user UNIQUE (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE point_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    point_account_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    reason VARCHAR(255),
    source_type VARCHAR(20) NOT NULL,
    source_id BIGINT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_point_tx_type_source UNIQUE (type, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_publication (
    id BINARY(16) NOT NULL,
    publication_date DATETIME(6) NOT NULL,
    listener_id VARCHAR(255) NOT NULL,
    serialized_event VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    completion_date DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
