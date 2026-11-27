ALTER TABLE reservation
    ADD COLUMN original_reservation_id BIGINT NULL,
    ADD CONSTRAINT uk_reservation_original UNIQUE (original_reservation_id),
    ADD CONSTRAINT fk_reservation_original FOREIGN KEY (original_reservation_id) REFERENCES reservation (id);

CREATE TABLE reservation_alternative_time (
    reservation_id BIGINT NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_id, reserved_at),
    FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE CASCADE
);

CREATE TABLE reservation_alternative_restaurant (
    reservation_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    PRIMARY KEY (reservation_id, display_order),
    FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE CASCADE
);
