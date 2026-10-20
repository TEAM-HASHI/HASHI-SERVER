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
        'MAGAZINE_CARD_NEWS',
        'NOTICE_DETAIL',
        'PHOTO_DETAIL'
    ));
