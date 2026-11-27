ALTER TABLE users
    ADD COLUMN invite_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN invited_by_user_id BIGINT NULL,
    ADD CONSTRAINT uk_users_invite_code UNIQUE (invite_code),
    ADD CONSTRAINT fk_users_inviter FOREIGN KEY (invited_by_user_id) REFERENCES users (id);
