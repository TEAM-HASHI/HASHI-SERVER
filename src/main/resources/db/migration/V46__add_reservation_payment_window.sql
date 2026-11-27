ALTER TABLE reservation
    ADD COLUMN payment_wait_started_at DATETIME(6) NULL,
    ADD COLUMN payment_deadline DATETIME(6) NULL;
