CREATE TABLE menu_exchange_day (
    applied_date DATE PRIMARY KEY,
    attempts INT NOT NULL DEFAULT 0,
    retry_after DATETIME(6) NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE menu_exchange_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    applied_date DATE NOT NULL,
    quoted_date DATE NOT NULL,
    krw_per_100_jpy DECIMAL(12,4) NOT NULL,
    source VARCHAR(12) NOT NULL,
    admin_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_menu_exchange_revision_day FOREIGN KEY (applied_date) REFERENCES menu_exchange_day(applied_date),
    CONSTRAINT chk_menu_exchange_rate CHECK (krw_per_100_jpy > 0 AND quoted_date <= applied_date),
    INDEX ix_menu_exchange_revision_date (applied_date, id)
);
