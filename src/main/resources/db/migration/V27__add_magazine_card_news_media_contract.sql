-- V26 is reserved by the magazine detail PR (#209).
ALTER TABLE image_asset
    DROP CHECK ck_image_asset_purpose,
    ADD CONSTRAINT ck_image_asset_purpose CHECK (purpose IN (
        'PROFILE',
        'REVIEW',
        'RESTAURANT',
        'RESTAURANT_MENU',
        'MAGAZINE_BANNER',
        'MAGAZINE_THUMBNAIL',
        'MAGAZINE_CARD_NEWS'
    ));

ALTER TABLE image_rendition
    DROP CHECK ck_image_rendition_role,
    ADD CONSTRAINT ck_image_rendition_role CHECK (role IN (
        'PROFILE_AVATAR',
        'RESTAURANT_THUMBNAIL',
        'RESTAURANT_CARD',
        'RESTAURANT_HERO',
        'MENU_LIST',
        'MENU_DETAIL',
        'REVIEW_PREVIEW',
        'REVIEW_DETAIL',
        'MAGAZINE_BANNER',
        'MAGAZINE_THUMBNAIL',
        'MAGAZINE_CARD_NEWS'
    ));
