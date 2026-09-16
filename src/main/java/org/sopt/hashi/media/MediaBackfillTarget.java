package org.sopt.hashi.media;

/** 소유 테이블의 이미지 슬롯을 고정한다. S3 key의 prefix로 purpose를 추측하지 않는다. */
public enum MediaBackfillTarget {

    RESTAURANT_IMAGE("RESTAURANT_IMAGE", "IMAGE", MediaAssetPurpose.RESTAURANT),
    RESTAURANT_MENU("RESTAURANT_MENU", "IMAGE", MediaAssetPurpose.RESTAURANT_MENU),
    USER_PROFILE("USER", "PROFILE", MediaAssetPurpose.PROFILE),
    MAGAZINE_BANNER("MAGAZINE", "BANNER", MediaAssetPurpose.MAGAZINE_BANNER),
    MAGAZINE_THUMBNAIL("MAGAZINE", "THUMBNAIL", MediaAssetPurpose.MAGAZINE_THUMBNAIL),
    REVIEW_IMAGE("REVIEW_IMAGE", "IMAGE", MediaAssetPurpose.REVIEW);

    private final String associationKind;
    private final String slot;
    private final MediaAssetPurpose purpose;

    MediaBackfillTarget(String associationKind, String slot, MediaAssetPurpose purpose) {
        this.associationKind = associationKind;
        this.slot = slot;
        this.purpose = purpose;
    }

    /** 이미 발급한 identity를 보존하기 위해 이 marker는 표시 이름처럼 변경하지 않는다. */
    public String associationKind() {
        return associationKind;
    }

    public String slot() {
        return slot;
    }

    public MediaAssetPurpose purpose() {
        return purpose;
    }
}
