CREATE TABLE media_pipeline_config (
    id TINYINT NOT NULL,
    current_spec_version INT NOT NULL,
    current_spec_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issuance_enabled BOOLEAN NOT NULL,
    lock_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_media_pipeline_config_singleton CHECK (id = 1),
    CONSTRAINT ck_media_pipeline_config_spec_version CHECK (current_spec_version >= 1),
    CONSTRAINT ck_media_pipeline_config_digest CHECK (
        current_spec_digest REGEXP '^[0-9a-f]{64}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO media_pipeline_config (
    id,
    current_spec_version,
    current_spec_digest,
    issuance_enabled,
    lock_version,
    updated_at
) VALUES (
    1,
    1,
    '1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f',
    FALSE,
    0,
    CURRENT_TIMESTAMP(6)
);

CREATE TABLE image_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    creation_origin VARCHAR(30) NOT NULL,
    creator_actor_type VARCHAR(30),
    creator_subject_id BIGINT,
    owner_actor_type VARCHAR(30) NOT NULL,
    owner_subject_id BIGINT,
    original_object_key VARCHAR(500) NOT NULL,
    declared_content_type VARCHAR(50) NOT NULL,
    declared_bytes BIGINT NOT NULL,
    upload_expires_at DATETIME(6) NOT NULL,
    source_version_id VARCHAR(1024),
    source_etag VARCHAR(255),
    actual_content_type VARCHAR(50),
    actual_bytes BIGINT,
    source_width INT,
    source_height INT,
    source_checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    processing_status VARCHAR(30) NOT NULL,
    binding_status VARCHAR(20) NOT NULL,
    active_spec_version INT,
    active_spec_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    target_spec_version INT,
    target_spec_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    target_processing_status VARCHAR(20),
    current_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    last_issued_spec_version INT,
    last_failure_spec_version INT,
    last_failure_code VARCHAR(64),
    backfill_identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    cleanup_status VARCHAR(20) NOT NULL,
    purge_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    purge_started_at DATETIME(6),
    objects_purged_at DATETIME(6),
    lock_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_image_asset_public_id UNIQUE (public_id),
    CONSTRAINT uk_image_asset_original_object_key UNIQUE (original_object_key),
    CONSTRAINT uk_image_asset_backfill_identity_hash UNIQUE (backfill_identity_hash),
    CONSTRAINT ck_image_asset_purpose CHECK (purpose IN (
        'PROFILE',
        'REVIEW',
        'RESTAURANT',
        'RESTAURANT_MENU',
        'MAGAZINE_BANNER',
        'MAGAZINE_THUMBNAIL'
    )),
    CONSTRAINT ck_image_asset_creation_origin CHECK (creation_origin IN (
        'DIRECT_UPLOAD',
        'SYSTEM_BACKFILL'
    )),
    CONSTRAINT ck_image_asset_actor CHECK (
        (
            creation_origin = 'DIRECT_UPLOAD'
            AND creator_actor_type IN ('USER', 'ADMIN', 'ONBOARDING')
            AND creator_subject_id IS NOT NULL
            AND owner_actor_type IN ('USER', 'ADMIN', 'ONBOARDING')
            AND owner_subject_id IS NOT NULL
        )
        OR (
            creation_origin = 'SYSTEM_BACKFILL'
            AND creator_actor_type IS NULL
            AND creator_subject_id IS NULL
            AND owner_actor_type = 'SYSTEM_BACKFILL'
            AND owner_subject_id IS NULL
        )
    ),
    CONSTRAINT ck_image_asset_declared_bytes CHECK (declared_bytes > 0),
    CONSTRAINT ck_image_asset_processing_status CHECK (processing_status IN (
        'PENDING_UPLOAD',
        'PROCESSING',
        'READY',
        'FAILED',
        'EXPIRED'
    )),
    CONSTRAINT ck_image_asset_binding_status CHECK (binding_status IN (
        'UNBOUND',
        'BOUND',
        'RETIRED'
    )),
    CONSTRAINT ck_image_asset_cleanup_status CHECK (cleanup_status IN (
        'ACTIVE',
        'PURGING',
        'PURGED'
    )),
    CONSTRAINT ck_image_asset_source_identity CHECK (
        (source_version_id IS NULL AND source_etag IS NULL)
        OR (source_version_id IS NOT NULL AND source_etag IS NOT NULL)
    ),
    CONSTRAINT ck_image_asset_verified_source CHECK (
        (
            actual_content_type IS NULL
            AND actual_bytes IS NULL
            AND source_width IS NULL
            AND source_height IS NULL
            AND source_checksum_sha256 IS NULL
        )
        OR (
            actual_content_type IS NOT NULL
            AND actual_bytes IS NOT NULL
            AND actual_bytes > 0
            AND source_width IS NOT NULL
            AND source_width > 0
            AND source_height IS NOT NULL
            AND source_height > 0
            AND source_checksum_sha256 IS NOT NULL
            AND source_checksum_sha256 REGEXP '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT ck_image_asset_ready_source CHECK (
        processing_status <> 'READY' OR actual_content_type IS NOT NULL
    ),
    CONSTRAINT ck_image_asset_active_spec CHECK (
        (active_spec_version IS NULL AND active_spec_digest IS NULL)
        OR (
            active_spec_version IS NOT NULL
            AND active_spec_version >= 1
            AND active_spec_digest IS NOT NULL
            AND active_spec_digest REGEXP '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT ck_image_asset_target_spec CHECK (
        (
            target_spec_version IS NULL
            AND target_spec_digest IS NULL
            AND target_processing_status IS NULL
            AND current_job_id IS NULL
        )
        OR (
            target_spec_version IS NOT NULL
            AND target_spec_version >= 1
            AND target_spec_digest IS NOT NULL
            AND target_spec_digest REGEXP '^[0-9a-f]{64}$'
            AND target_processing_status IS NOT NULL
            AND target_processing_status = 'PROCESSING'
            AND current_job_id IS NOT NULL
        )
    ),
    CONSTRAINT ck_image_asset_last_issued_spec CHECK (
        last_issued_spec_version IS NULL OR last_issued_spec_version >= 1
    ),
    CONSTRAINT ck_image_asset_last_failure CHECK (
        (last_failure_spec_version IS NULL AND last_failure_code IS NULL)
        OR (
            last_failure_spec_version IS NOT NULL
            AND last_failure_spec_version >= 1
            AND last_failure_code IS NOT NULL
        )
    ),
    CONSTRAINT ck_image_asset_backfill_identity CHECK (
        (creation_origin = 'DIRECT_UPLOAD' AND backfill_identity_hash IS NULL)
        OR (
            creation_origin = 'SYSTEM_BACKFILL'
            AND backfill_identity_hash IS NOT NULL
            AND backfill_identity_hash REGEXP '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT ck_image_asset_cleanup_lease CHECK (
        (
            cleanup_status = 'ACTIVE'
            AND purge_token IS NULL
            AND purge_started_at IS NULL
            AND objects_purged_at IS NULL
        )
        OR (
            cleanup_status = 'PURGING'
            AND purge_token IS NOT NULL
            AND purge_started_at IS NOT NULL
            AND objects_purged_at IS NULL
        )
        OR (
            cleanup_status = 'PURGED'
            AND purge_token IS NOT NULL
            AND purge_started_at IS NOT NULL
            AND objects_purged_at IS NOT NULL
        )
    ),
    INDEX idx_image_asset_owner (owner_actor_type, owner_subject_id, public_id),
    INDEX idx_image_asset_processing_scan (cleanup_status, processing_status, updated_at, id),
    INDEX idx_image_asset_cleanup_scan (
        cleanup_status,
        binding_status,
        processing_status,
        updated_at,
        id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE image_rendition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    image_asset_id BIGINT NOT NULL,
    role VARCHAR(40) NOT NULL,
    spec_version INT NOT NULL,
    format VARCHAR(20) NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    bytes BIGINT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_image_rendition_asset
        FOREIGN KEY (image_asset_id) REFERENCES image_asset (id),
    CONSTRAINT uk_image_rendition_identity UNIQUE (
        image_asset_id,
        role,
        spec_version,
        format,
        width
    ),
    CONSTRAINT uk_image_rendition_object_key UNIQUE (object_key),
    CONSTRAINT ck_image_rendition_role CHECK (role IN (
        'PROFILE_AVATAR',
        'RESTAURANT_THUMBNAIL',
        'RESTAURANT_CARD',
        'RESTAURANT_HERO',
        'MENU_LIST',
        'MENU_DETAIL',
        'REVIEW_PREVIEW',
        'REVIEW_DETAIL',
        'MAGAZINE_BANNER',
        'MAGAZINE_THUMBNAIL'
    )),
    CONSTRAINT ck_image_rendition_spec_version CHECK (spec_version >= 1),
    CONSTRAINT ck_image_rendition_format CHECK (
        format = 'WEBP' AND mime_type = 'image/webp'
    ),
    CONSTRAINT ck_image_rendition_dimensions CHECK (
        width > 0 AND height > 0 AND bytes > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE event_publication
    MODIFY COLUMN serialized_event VARCHAR(4000) NOT NULL,
    MODIFY COLUMN listener_id VARCHAR(512) NOT NULL,
    MODIFY COLUMN event_type VARCHAR(512) NOT NULL;

CREATE INDEX idx_event_publication_completion_date
    ON event_publication (completion_date);
