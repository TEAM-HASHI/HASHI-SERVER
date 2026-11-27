ALTER TABLE point_account
    ADD COLUMN pending_reclaim BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_point_account_pending_reclaim CHECK (pending_reclaim >= 0);
